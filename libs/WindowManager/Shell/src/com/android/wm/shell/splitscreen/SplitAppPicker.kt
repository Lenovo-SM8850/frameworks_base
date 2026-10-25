// SPDX-License-Identifier: Apache-2.0

package com.android.wm.shell.splitscreen

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.android.wm.shell.R
import com.android.wm.shell.desktopmode.WindowPreviewOutline
import com.android.wm.shell.shared.search.AppLabelSearch
import java.text.Collator

/** Inputful Shell panel, independently positioned from its inputless destination outline. */
class SplitAppPicker(
    private val context: Context,
    stable: Rect,
    destination: Rect,
    private val cancel: () -> Unit,
    private val select: (SplitPickerController.App) -> Unit,
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val res = context.resources
    private val root = object : LinearLayout(context) {
        private val back = OnBackInvokedCallback { handleBack() }
        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            findOnBackInvokedDispatcher()?.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, back)
        }
        override fun onDetachedFromWindow() {
            findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(back)
            super.onDetachedFromWindow()
        }
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
                if (event.action == KeyEvent.ACTION_UP) handleBack()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }
    private val outlineView = View(context)
    private val outline = WindowPreviewOutline(outlineView)
    private val search = EditText(context)
    private val content = LinearLayout(context)
    private var entries = emptyList<SplitPickerController.App>()
    private var launching = false
    private var attached = false
    private val padding = px(R.dimen.split_picker_padding)
    private val panel = SplitPickerPolicy.panelBounds(stable, destination,
        px(R.dimen.split_picker_max_width), px(R.dimen.split_picker_max_height))
    private val columns = (panel.width() / px(R.dimen.split_picker_cell_min_width)).coerceAtLeast(1)
    private val cellWidth = ((panel.width() - 2 * padding) / columns).coerceAtLeast(1)

    init {
        val touchTarget = px(R.dimen.split_picker_touch_target)
        root.orientation = LinearLayout.VERTICAL
        root.layoutDirection = res.configuration.layoutDirection
        root.setPadding(padding, px(R.dimen.split_picker_padding_top), padding, padding)
        root.isFocusableInTouchMode = true
        root.elevation = res.getDimension(R.dimen.split_picker_elevation)
        root.setBackgroundResource(R.drawable.split_picker_background)
        root.clipToOutline = true
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(label(R.style.SplitPickerTitle,
            context.getString(R.string.split_picker_title)).apply {
            minHeight = touchTarget
            isAccessibilityHeading = true
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(label(R.style.SplitPickerButton,
            context.getString(R.string.split_picker_close)).apply {
            val buttonPadding = px(R.dimen.split_picker_button_padding)
            gravity = Gravity.CENTER
            minWidth = touchTarget
            minimumHeight = touchTarget
            setPaddingRelative(buttonPadding, 0, buttonPadding, 0)
            isFocusable = true
            isClickable = true
            setOnClickListener { cancel() }
            setBackgroundResource(R.drawable.split_picker_button_background)
        })
        root.addView(header)
        search.hint = context.getString(R.string.split_picker_search)
        search.contentDescription = search.hint
        search.setTextAppearance(R.style.SplitPickerSearch)
        search.setBackgroundResource(R.drawable.split_picker_search_background)
        search.gravity = Gravity.CENTER_VERTICAL
        search.setPaddingRelative(px(R.dimen.split_picker_search_padding), 0,
            px(R.dimen.split_picker_search_padding), 0)
        search.setCompoundDrawablesRelativeWithIntrinsicBounds(
            R.drawable.split_picker_ic_search, 0, 0, 0)
        search.compoundDrawablePadding = px(R.dimen.split_picker_search_icon_padding)
        search.compoundDrawableTintList = ColorStateList.valueOf(
            context.getColor(com.android.internal.R.color.materialColorOnSurfaceVariant))
        search.setSingleLine()
        search.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        search.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        // Single-line text input sets a one-line height, so apply the minimum height after it.
        search.minHeight = px(R.dimen.split_picker_search_height)
        search.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard()
                true
            } else false
        }
        root.addView(search, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        content.orientation = LinearLayout.VERTICAL
        root.addView(ScrollView(context).apply { addView(content) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { render() }
            override fun afterTextChanged(s: Editable?) {}
        })
        content.addView(label(R.style.SplitPickerBody,
            context.getString(R.string.split_picker_loading)))
    }

    fun show(destination: Rect) {
        try {
            val outlineParams = params(-1, -1, LayoutParams.FLAG_NOT_FOCUSABLE or
                LayoutParams.FLAG_NOT_TOUCHABLE or LayoutParams.FLAG_LAYOUT_IN_SCREEN)
            outlineParams.setFitInsetsTypes(0)
            wm.addView(outlineView, outlineParams)
            val lp = params(panel.width(), panel.height(), LayoutParams.FLAG_NOT_TOUCH_MODAL or
                LayoutParams.FLAG_SPLIT_TOUCH)
            lp.gravity = Gravity.TOP or Gravity.LEFT
            lp.x = panel.left
            lp.y = panel.top
            lp.setFitInsetsTypes(android.view.WindowInsets.Type.ime())
            lp.softInputMode = LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
            lp.windowAnimations = android.R.style.Animation_Dialog
            wm.addView(root, lp)
            attached = true
            outline.update(destination,
                res.getDimension(R.dimen.desktop_mode_handle_menu_corner_radius))
            if (res.configuration.keyboard != Configuration.KEYBOARD_NOKEYS &&
                res.configuration.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO) {
                search.requestFocus()
            } else root.requestFocus()
            root.announceForAccessibility(context.getString(R.string.split_picker_title))
        } catch (e: RuntimeException) {
            close()
            throw e
        }
    }

    fun bind(apps: List<SplitPickerController.App>) {
        if (!attached) return
        entries = apps
        launching = false
        render()
    }

    fun launching() {
        launching = true
        root.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        root.announceForAccessibility(context.getString(R.string.split_picker_launching))
    }

    private fun render() {
        content.removeAllViews()
        val query = search.text.toString().trim()
        val matcher = AppLabelSearch(res.configuration.locales[0])
        val filtered = entries.filter { matcher.matches(it.label, query) }
        val recent = filtered.filter { it.recentTaskId != null }.sortedBy { it.recentOrder }
        if (recent.isNotEmpty()) section(R.string.split_picker_recent, recent)
        val collator = Collator.getInstance()
        section(R.string.split_picker_all_apps,
            filtered.sortedWith { a, b -> collator.compare(a.label, b.label) })
        if (filtered.isEmpty()) content.addView(label(R.style.SplitPickerBody,
            context.getString(
                if (query.isEmpty()) R.string.split_picker_empty else R.string.split_picker_no_results)))
    }

    private fun section(title: Int, apps: List<SplitPickerController.App>) {
        content.addView(label(R.style.SplitPickerSection,
            context.getString(title)).apply {
            isAccessibilityHeading = true
            setPadding(0, px(R.dimen.split_picker_section_padding_top), 0,
                px(R.dimen.split_picker_section_padding_bottom))
        })
        val grid = GridLayout(context).apply {
            columnCount = columns
            layoutDirection = res.configuration.layoutDirection
        }
        val cellPaddingHorizontal = px(R.dimen.split_picker_cell_padding_horizontal)
        val cellPaddingVertical = px(R.dimen.split_picker_cell_padding_vertical)
        val iconSize = px(R.dimen.split_picker_app_icon_size)
        for ((index, app) in apps.withIndex()) {
            val available = app.availability == SplitPickerPolicy.Availability.AVAILABLE
            val reason = if (app.availability == SplitPickerPolicy.Availability.ALREADY_OPEN)
                R.string.split_picker_already_open else R.string.split_picker_unavailable
            val cell = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                // Top-aligned so the icons of a row line up whatever the label length.
                gravity = Gravity.CENTER_HORIZONTAL
                minimumHeight = px(R.dimen.split_picker_cell_min_height)
                setPadding(cellPaddingHorizontal, cellPaddingVertical,
                    cellPaddingHorizontal, cellPaddingVertical)
                isEnabled = available
                isFocusable = true
                isClickable = available
                alpha = if (available) 1f else res.getFloat(R.dimen.split_picker_disabled_alpha)
                contentDescription = if (available) app.badgedLabel else
                    context.getString(R.string.split_picker_disabled_app,
                        app.badgedLabel, context.getString(reason))
                setBackgroundResource(R.drawable.split_picker_cell_background)
                setOnClickListener { if (!launching) select(app) }
            }
            cell.addView(ImageView(context).apply {
                setImageDrawable(app.icon.constantState?.newDrawable(res) ?: app.icon)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(iconSize, iconSize))
            cell.addView(label(R.style.SplitPickerLabel, app.label).apply {
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = px(R.dimen.split_picker_app_icon_label_gap)
            })
            if (!available) cell.addView(label(R.style.SplitPickerCaption,
                context.getString(reason)).apply {
                gravity = Gravity.CENTER
                maxLines = 2
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
            grid.addView(cell, GridLayout.LayoutParams().apply {
                width = cellWidth
                columnSpec = GridLayout.spec(index % columns)
                rowSpec = GridLayout.spec(index / columns)
                height = -2
            })
        }
        content.addView(grid, LinearLayout.LayoutParams(-1, -2))
    }

    private fun hideKeyboard() {
        context.getSystemService(InputMethodManager::class.java)
            .hideSoftInputFromWindow(root.windowToken, 0)
    }

    private fun handleBack() {
        if (root.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true) {
            hideKeyboard()
        } else if (search.hasFocus() || search.text.isNotEmpty()) {
            search.setText("")
            search.clearFocus()
            root.requestFocus()
        } else cancel()
    }

    fun close() {
        hideKeyboard()
        outline.clear()
        if (root.isAttachedToWindow) wm.removeViewImmediate(root)
        if (outlineView.isAttachedToWindow) wm.removeViewImmediate(outlineView)
        attached = false
    }

    private fun params(width: Int, height: Int, flags: Int) = LayoutParams(width, height,
        LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT).apply {
        privateFlags = privateFlags or LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY
        title = context.getString(R.string.split_picker_title)
    }

    private fun label(appearance: Int, value: CharSequence) = TextView(context).apply {
        setTextAppearance(appearance)
        text = value
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun px(id: Int) = res.getDimensionPixelSize(id)
}
