package com.myAllVideoBrowser.ui.component.dialog

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import com.google.android.material.textfield.TextInputEditText
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.util.AppUtil

fun showRenameVideoDialog(
    context: Context,
    appUtil: AppUtil,
    currentName: String,
    onClickListener: View.OnClickListener
) {
    val etName = TextInputEditText(context).apply {
        layoutParams =
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setText(currentName)
        text?.let { setSelection(it.length) }
        setTextColor(Color.BLACK)
        imeOptions = EditorInfo.IME_ACTION_DONE
        inputType = InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        setSingleLine()
    }

    val layout = LinearLayout(context).apply {
        layoutParams =
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        orientation = LinearLayout.VERTICAL
        setPadding(80, 40, 80, 20)
        addView(etName)
       Handler(Looper.myLooper()!!).postDelayed({
           appUtil.showSoftKeyboard(etName)
       }, 400)
    }

    appUtil.showSoftKeyboard(etName)

    AlertDialog.Builder(context)
        .setTitle(context.getString(R.string.video_rename_title))
        .setView(layout)
        .setNegativeButton(context.getString(android.R.string.cancel)) { _, _ ->
            appUtil.hideSoftKeyboard(etName)
        }
        .setPositiveButton(context.getString(android.R.string.ok)) { _, _ ->
            appUtil.hideSoftKeyboard(etName)
            onClickListener.onClick(etName)
        }.show()
}