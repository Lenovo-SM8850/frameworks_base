// SPDX-License-Identifier: Apache-2.0

package com.android.wm.shell.splitscreen

import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.android.wm.shell.R
import com.android.wm.shell.desktopmode.WindowPreviewOutline
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
    private val foregroundColor = context.getColor(com.android.internal.R.color.materialColorOnSurface)
    private val root = object : LinearLayout(context) {
        private val back = OnBackInvokedCallback { cancel() }
        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            findOnBackInvokedDispatcher()?.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_OVERLAY, back)
        }
        override fun onDetachedFromWindow() {
            findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(back)
            super.onDetachedFromWindow()
        }
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
                if (event.action == KeyEvent.ACTION_UP) cancel()
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
    private val panel = SplitPickerPolicy.panelBounds(stable, destination, dp(480), dp(560))
    private val columns = (panel.width() / dp(104)).coerceAtLeast(1)
    private val cellWidth = ((panel.width() - 2 * dp(16)) / columns).coerceAtLeast(1)

    init {
        root.orientation = LinearLayout.VERTICAL
        root.layoutDirection = context.resources.configuration.layoutDirection
        root.setPadding(dp(16), dp(12), dp(16), dp(16))
        root.isFocusableInTouchMode = true
        root.elevation = dp(8).toFloat()
        root.background = GradientDrawable().apply {
            setColor(context.getColor(com.android.internal.R.color.materialColorSurfaceContainerHigh))
            cornerRadius = dp(28).toFloat()
        }
        root.clipToOutline = true
        root.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) { cancel(); true } else false
        }
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(text(context.getString(R.string.split_picker_title)).apply {
            textSize = 20f
            isAccessibilityHeading = true
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        header.addView(text(context.getString(R.string.split_picker_close)).apply {
            minWidth = dp(48)
            minimumHeight = dp(48)
            isFocusable = true
            isClickable = true
            setOnClickListener { cancel() }
            setBackgroundResource(android.R.drawable.list_selector_background)
        })
        root.addView(header)
        search.hint = context.getString(R.string.split_picker_search)
        search.contentDescription = search.hint
        search.setTextColor(foregroundColor)
        search.setSingleLine()
        search.minimumHeight = dp(48)
        root.addView(search, LinearLayout.LayoutParams(-1, dp(56)))
        content.orientation = LinearLayout.VERTICAL
        root.addView(ScrollView(context).apply { addView(content) },
            LinearLayout.LayoutParams(-1, 0, 1f))
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { render() }
            override fun afterTextChanged(s: Editable?) {}
        })
        content.addView(text(context.getString(R.string.split_picker_loading)))
    }

    fun show(destination: Rect) {
        try {
            val outlineParams = params(-1, -1, LayoutParams.FLAG_NOT_FOCUSABLE or
                LayoutParams.FLAG_NOT_TOUCHABLE or LayoutParams.FLAG_LAYOUT_IN_SCREEN)
            outlineParams.setFitInsetsTypes(0)
            wm.addView(outlineView, outlineParams)
            val lp = params(panel.width(), panel.height(), LayoutParams.FLAG_NOT_TOUCH_MODAL or
                LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or LayoutParams.FLAG_SPLIT_TOUCH)
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
                context.resources.getDimension(R.dimen.desktop_mode_handle_menu_corner_radius))
            if (context.resources.configuration.keyboard != Configuration.KEYBOARD_NOKEYS &&
                context.resources.configuration.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO) {
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
        val filtered = entries.filter { SplitPickerPolicy.matchesQuery(it.label, query) }
        val recent = filtered.filter { it.recentTaskId != null }.sortedBy { it.recentOrder }
        if (recent.isNotEmpty()) section(R.string.split_picker_recent, recent)
        val collator = Collator.getInstance()
        section(R.string.split_picker_all_apps,
            filtered.sortedWith { a, b -> collator.compare(a.label, b.label) })
        if (filtered.isEmpty()) content.addView(text(context.getString(R.string.split_picker_empty)))
    }

    private fun section(title: Int, apps: List<SplitPickerController.App>) {
        content.addView(text(context.getString(title)).apply {
            isAccessibilityHeading = true
            setPadding(0, dp(16), 0, dp(8))
        })
        val grid = GridLayout(context).apply {
            columnCount = columns
            layoutDirection = context.resources.configuration.layoutDirection
        }
        for ((index, app) in apps.withIndex()) {
            val available = app.availability == SplitPickerPolicy.Availability.AVAILABLE
            val reason = if (app.availability == SplitPickerPolicy.Availability.ALREADY_OPEN)
                R.string.split_picker_already_open else R.string.split_picker_unavailable
            val cell = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                minimumHeight = dp(104)
                setPadding(dp(4), dp(8), dp(4), dp(8))
                isEnabled = available
                isFocusable = true
                isClickable = available
                alpha = if (available) 1f else 0.5f
                contentDescription = if (available) app.badgedLabel else
                    context.getString(R.string.split_picker_disabled_app,
                        app.badgedLabel, context.getString(reason))
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener { if (!launching) select(app) }
            }
            cell.addView(ImageView(context).apply {
                setImageDrawable(app.icon.constantState?.newDrawable(context.resources) ?: app.icon)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            cell.addView(text(app.label).apply {
                gravity = Gravity.CENTER
                textSize = 12f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(-1, dp(40)))
            if (!available) cell.addView(text(context.getString(reason)).apply {
                textSize = 11f
                gravity = Gravity.CENTER
                maxLines = 2
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(-1, dp(36)))
            grid.addView(cell, GridLayout.LayoutParams().apply {
                width = cellWidth
                columnSpec = GridLayout.spec(index % columns)
                rowSpec = GridLayout.spec(index / columns)
                height = -2
            })
        }
        content.addView(grid, LinearLayout.LayoutParams(-1, -2))
    }

    fun close() {
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

    private fun text(value: CharSequence) = TextView(context).apply {
        text = value
        setTextColor(foregroundColor)
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
