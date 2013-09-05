/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.webkit;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.animation.ObjectAnimator;
import android.annotation.Widget;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.DataSetObserver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.DrawFilter;
import android.graphics.Paint;
import android.graphics.PaintFlagsDrawFilter;
import android.graphics.Picture;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.graphics.RegionIterator;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.net.Proxy;
import android.net.ProxyProperties;
import android.net.Uri;
import android.net.http.SslCertificate;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.security.KeyChain;
import android.text.Editable;
import android.text.InputType;
import android.text.Selection;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.EventLog;
import android.util.Log;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.HardwareCanvas;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.SoundEffectConstants;
import android.view.VelocityTracker;
import android.view.View;
import android.view.View.MeasureSpec;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewRootImpl;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebView.HitTestResult;
import android.webkit.WebView.PictureListener;
import android.webkit.WebViewCore.DrawData;
import android.webkit.WebViewCore.EventHub;
import android.webkit.WebViewCore.TextFieldInitData;
import android.webkit.WebViewCore.TextSelectionData;
import android.webkit.WebViewCore.WebKitHitTest;
import android.widget.AbsoluteLayout;
import android.widget.Adapter;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ArrayAdapter;
import android.widget.CheckedTextView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.OverScroller;
import android.widget.PopupWindow;
import android.widget.Scroller;
import android.widget.TextView;
import android.widget.Toast;

import junit.framework.Assert;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Vector;

/**
 * Implements a backend provider for the {@link WebView} public API.
 * @hide
 */
// TODO: Check if any WebView published API methods are called from within here, and if so
// we should bounce the call out via the proxy to enable any sub-class to override it.
@Widget
@SuppressWarnings("deprecation")
public final class WebViewClassic implements WebViewProvider, WebViewProvider.ScrollDelegate,
        WebViewProvider.ViewDelegate {
    /**
     * InputConnection used for ContentEditable. This captures changes
     * to the text and sends them either as key strokes or text changes.
     */
    class WebViewInputConnection extends BaseInputConnection {
        // Used for mapping characters to keys typed.
        private KeyCharacterMap mKeyCharacterMap;
        private boolean mIsKeySentByMe;
        private int mInputType;
        private int mImeOptions;
        private String mHint;
        private int mMaxLength;
        private boolean mIsAutoFillable;
        private boolean mIsAutoCompleteEnabled;
        private String mName;
        private int mBatchLevel;

        public WebViewInputConnection() {
            super(mWebView, true);
        }

        public void setAutoFillable(int queryId) {
            mIsAutoFillable = getSettings().getAutoFillEnabled()
                    && (queryId != WebTextView.FORM_NOT_AUTOFILLABLE);
            int variation = mInputType & EditorInfo.TYPE_MASK_VARIATION;
            if (variation != EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD
                    && (mIsAutoFillable || mIsAutoCompleteEnabled)) {
                if (mName != null && mName.length() > 0) {
                    requestFormData(mName, mFieldPointer, mIsAutoFillable,
                            mIsAutoCompleteEnabled);
                }
            }
        }

        @Override
        public boolean beginBatchEdit() {
            if (mBatchLevel == 0) {
                beginTextBatch();
            }
            mBatchLevel++;
            return false;
        }

        @Override
        public boolean endBatchEdit() {
            mBatchLevel--;
            if (mBatchLevel == 0) {
                commitTextBatch();
            }
            return false;
        }

        public boolean getIsAutoFillable() {
            return mIsAutoFillable;
        }

        @Override
        public boolean sendKeyEvent(KeyEvent event) {
            // Some IMEs send key events directly using sendKeyEvents.
            // WebViewInputConnection should treat these as text changes.
            if (!mIsKeySentByMe) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    if (event.getKeyCode() == KeyEvent.KEYCODE_DEL) {
                        return deleteSurroundingText(1, 0);
                    } else if (event.getKeyCode() == KeyEvent.KEYCODE_FORWARD_DEL) {
                        return deleteSurroundingText(0, 1);
                    } else if (event.getUnicodeChar() != 0){
                        String newComposingText =
                                Character.toString((char)event.getUnicodeChar());
                        return commitText(newComposingText, 1);
                    }
                } else if (event.getAction() == KeyEvent.ACTION_DOWN &&
                        (event.getKeyCode() == KeyEvent.KEYCODE_DEL
                        || event.getKeyCode() == KeyEvent.KEYCODE_FORWARD_DEL
                        || event.getUnicodeChar() != 0)) {
                    return true; // only act on action_down
                }
            }
            return super.sendKeyEvent(event);
        }

        public void setTextAndKeepSelection(CharSequence text) {
            Editable editable = getEditable();
            int selectionStart = Selection.getSelectionStart(editable);
            int selectionEnd = Selection.getSelectionEnd(editable);
            text = limitReplaceTextByMaxLength(text, editable.length());
            editable.replace(0, editable.length(), text);
            restartInput();
            // Keep the previous selection.
            selectionStart = Math.min(selectionStart, editable.length());
            selectionEnd = Math.min(selectionEnd, editable.length());
            setSelection(selectionStart, selectionEnd);
            finishComposingText();
        }

        public void replaceSelection(CharSequence text) {
            Editable editable = getEditable();
            int selectionStart = Selection.getSelectionStart(editable);
            int selectionEnd = Selection.getSelectionEnd(editable);
            text = limitReplaceTextByMaxLength(text, selectionEnd - selectionStart);
            setNewText(selectionStart, selectionEnd, text);
            editable.replace(selectionStart, selectionEnd, text);
            restartInput();
            // Move caret to the end of the new text
            int newCaret = selectionStart + text.length();
            setSelection(newCaret, newCaret);
        }

        @Override
        public boolean setComposingText(CharSequence text, int newCursorPosition) {
            Editable editable = getEditable();
            int start = getComposingSpanStart(editable);
            int end = getComposingSpanEnd(editable);
            if (start < 0 || end < 0) {
                start = Selection.getSelectionStart(editable);
                end = Selection.getSelectionEnd(editable);
            }
            if (end < start) {
                int temp = end;
                end = start;
                start = temp;
            }
            CharSequence limitedText = limitReplaceTextByMaxLength(text, end - start);
            setNewText(start, end, limitedText);
            if (limitedText != text) {
                newCursorPosition -= text.length() - limitedText.length();
            }
            super.setComposingText(limitedText, newCursorPosition);
            updateSelection();
            if (limitedText != text) {
                int lastCaret = start + limitedText.length();
                finishComposingText();
                setSelection(lastCaret, lastCaret);
            }
            return true;
        }

        @Override
        public boolean commitText(CharSequence text, int newCursorPosition) {
            setComposingText(text, newCursorPosition);
            finishComposingText();
            return true;
        }

        @Override
        public boolean deleteSurroundingText(int leftLength, int rightLength) {
            // This code is from BaseInputConnection#deleteSurroundText.
            // We have to delete the same text in webkit.
            Editable content = getEditable();
            int a = Selection.getSelectionStart(content);
            int b = Selection.getSelectionEnd(content);

            if (a > b) {
                int tmp = a;
                a = b;
                b = tmp;
            }

            int ca = getComposingSpanStart(content);
            int cb = getComposingSpanEnd(content);
            if (cb < ca) {
                int tmp = ca;
                ca = cb;
                cb = tmp;
            }
            if (ca != -1 && cb != -1) {
                if (ca < a) a = ca;
                if (cb > b) b = cb;
            }

            int endDelete = Math.min(content.length(), b + rightLength);
            if (endDelete > b) {
                setNewText(b, endDelete, "");
            }
            int startDelete = Math.max(0, a - leftLength);
            if (startDelete < a) {
                setNewText(startDelete, a, "");
            }
            return super.deleteSurroundingText(leftLength, rightLength);
        }

        @Override
        public boolean performEditorAction(int editorAction) {

            boolean handled = true;
            switch (editorAction) {
            case EditorInfo.IME_ACTION_NEXT:
                mWebView.requestFocus(View.FOCUS_FORWARD);
                break;
            case EditorInfo.IME_ACTION_PREVIOUS:
                mWebView.requestFocus(View.FOCUS_BACKWARD);
                break;
            case EditorInfo.IME_ACTION_DONE:
                WebViewClassic.this.hideSoftKeyboard();
                break;
            case EditorInfo.IME_ACTION_GO:
            case EditorInfo.IME_ACTION_SEARCH:
                WebViewClassic.this.hideSoftKeyboard();
                String text = getEditable().toString();
                passToJavaScript(text, new KeyEvent(KeyEvent.ACTION_DOWN,
                        KeyEvent.KEYCODE_ENTER));
                passToJavaScript(text, new KeyEvent(KeyEvent.ACTION_UP,
                        KeyEvent.KEYCODE_ENTER));
                break;

            default:
                handled = super.performEditorAction(editorAction);
                break;
            }

            return handled;
        }

        public void initEditorInfo(WebViewCore.TextFieldInitData initData) {
            int type = initData.mType;
            int inputType = InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT;
            int imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
                    | EditorInfo.IME_FLAG_NO_FULLSCREEN;
            if (!initData.mIsSpellCheckEnabled) {
                inputType |= InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
            }
            if (WebTextView.TEXT_AREA != type) {
                if (initData.mIsTextFieldNext) {
                    imeOptions |= EditorInfo.IME_FLAG_NAVIGATE_NEXT;
                }
                if (initData.mIsTextFieldPrev) {
                    imeOptions |= EditorInfo.IME_FLAG_NAVIGATE_PREVIOUS;
                }
            }
            int action = EditorInfo.IME_ACTION_GO;
            switch (type) {
                case WebTextView.NORMAL_TEXT_FIELD:
                    break;
                case WebTextView.TEXT_AREA:
                    inputType |= InputType.TYPE_TEXT_FLAG_MULTI_LINE
                            | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                            | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT;
                    action = EditorInfo.IME_ACTION_NONE;
                    break;
                case WebTextView.PASSWORD:
                    inputType |= EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD;
                    break;
                case WebTextView.SEARCH:
                    action = EditorInfo.IME_ACTION_SEARCH;
                    break;
                case WebTextView.EMAIL:
                    // inputType needs to be overwritten because of the different text variation.
                    inputType = InputType.TYPE_CLASS_TEXT
                            | InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS;
                    break;
                case WebTextView.NUMBER:
                    // inputType needs to be overwritten because of the different class.
                    inputType = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_NORMAL
                            | InputType.TYPE_NUMBER_FLAG_SIGNED | InputType.TYPE_NUMBER_FLAG_DECIMAL;
                    // Number and telephone do not have both a Tab key and an
                    // action, so set the action to NEXT
                    break;
                case WebTextView.TELEPHONE:
                    // inputType needs to be overwritten because of the different class.
                    inputType = InputType.TYPE_CLASS_PHONE;
                    break;
                case WebTextView.URL:
                    // TYPE_TEXT_VARIATION_URI prevents Tab key from showing, so
                    // exclude it for now.
                    inputType |= InputType.TYPE_TEXT_VARIATION_URI;
                    break;
                default:
                    break;
            }
            imeOptions |= action;
            mHint = initData.mLabel;
            mInputType = inputType;
            mImeOptions = imeOptions;
            mMaxLength = initData.mMaxLength;
            mIsAutoCompleteEnabled = initData.mIsAutoCompleteEnabled;
            mName = initData.mName;
            mAutoCompletePopup.clearAdapter();
        }

        public void setupEditorInfo(EditorInfo outAttrs) {
            outAttrs.inputType = mInputType;
            outAttrs.imeOptions = mImeOptions;
            outAttrs.hintText = mHint;
            outAttrs.initialCapsMode = getCursorCapsMode(InputType.TYPE_CLASS_TEXT);

            Editable editable = getEditable();
            int selectionStart = Selection.getSelectionStart(editable);
            int selectionEnd = Selection.getSelectionEnd(editable);
            if (selectionStart < 0 || selectionEnd < 0) {
                selectionStart = editable.length();
                selectionEnd = selectionStart;
            }
            outAttrs.initialSelStart = selectionStart;
            outAttrs.initialSelEnd = selectionEnd;
        }

        @Override
        public boolean setSelection(int start, int end) {
            boolean result = super.setSelection(start, end);
            updateSelection();
            return result;
        }

        @Override
        public boolean setComposingRegion(int start, int end) {
            boolean result = super.setComposingRegion(start, end);
            updateSelection();
            return result;
        }

        /**
         * Send the selection and composing spans to the IME.
         */
        private void updateSelection() {
            Editable editable = getEditable();
            int selectionStart = Selection.getSelectionStart(editable);
            int selectionEnd = Selection.getSelectionEnd(editable);
            int composingStart = getComposingSpanStart(editable);
            int composingEnd = getComposingSpanEnd(editable);
            InputMethodManager imm = InputMethodManager.peekInstance();
            if (imm != null) {
                imm.updateSelection(mWebView, selectionStart, selectionEnd,
                        composingStart, composingEnd);
            }
        }

        /**
         * Sends a text change to webkit indirectly. If it is a single-
         * character add or delete, it sends it as a key stroke. If it cannot
         * be represented as a key stroke, it sends it as a field change.
         * @param start The start offset (inclusive) of the text being changed.
         * @param end The end offset (exclusive) of the text being changed.
         * @param text The new text to replace the changed text.
         */
        private void setNewText(int start, int end, CharSequence text) {
            mIsKeySentByMe = true;
            Editable editable = getEditable();
            CharSequence original = editable.subSequence(start, end);
            boolean isCharacterAdd = false;
            boolean isCharacterDelete = false;
            int textLength = text.length();
            int originalLength = original.length();
            int selectionStart = Selection.getSelectionStart(editable);
            int selectionEnd = Selection.getSelectionEnd(editable);
            if (selectionStart == selectionEnd) {
                if (textLength > originalLength) {
                    isCharacterAdd = (textLength == originalLength + 1)
                            && TextUtils.regionMatches(text, 0, original, 0,
                                    originalLength);
                } else if (originalLength > textLength) {
                    isCharacterDelete = (textLength == originalLength - 1)
                            && TextUtils.regionMatches(text, 0, original, 0,
                                    textLength);
                }
            }
            if (isCharacterAdd) {
                sendCharacter(text.charAt(textLength - 1));
            } else if (isCharacterDelete) {
                sendKey(KeyEvent.KEYCODE_DEL);
            } else if ((textLength != originalLength) ||
                    !TextUtils.regionMatches(text, 0, original, 0,
                            textLength)) {
                // Send a message so that key strokes and text replacement
                // do not come out of order.
                Message replaceMessage = mPrivateHandler.obtainMessage(
                        REPLACE_TEXT, start,  end, text.toString());
                mPrivateHandler.sendMessage(replaceMessage);
            }
            if (mAutoCompletePopup != null) {
                StringBuilder newText = new StringBuilder();
                newText.append(editable.subSequence(0, start));
                newText.append(text);
                newText.append(editable.subSequence(end, editable.length()));
                mAutoCompletePopup.setText(newText.toString());
            }
            mIsKeySentByMe = false;
        }

        /**
         * Send a single character to the WebView as a key down and up event.
         * @param c The character to be sent.
         */
        private void sendCharacter(char c) {
            if (mKeyCharacterMap == null) {
                mKeyCharacterMap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
            }
            char[] chars = new char[1];
            chars[0] = c;
            KeyEvent[] events = mKeyCharacterMap.getEvents(chars);
            if (events != null) {
                for (KeyEvent event : events) {
                    sendKeyEvent(event);
                }
            } else {
                Message msg = mPrivateHandler.obtainMessage(KEY_PRESS, (int) c, 0);
                mPrivateHandler.sendMessage(msg);
            }
        }

        /**
         * Send a key event for a specific key code, not a standard
         * unicode character.
         * @param keyCode The key code to send.
         */
        private void sendKey(int keyCode) {
            long eventTime = SystemClock.uptimeMillis();
            sendKeyEvent(new KeyEvent(eventTime, eventTime,
                    KeyEvent.ACTION_DOWN, keyCode, 0, 0,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                    KeyEvent.FLAG_SOFT_KEYBOARD));
            sendKeyEvent(new KeyEvent(SystemClock.uptimeMillis(), eventTime,
                    KeyEvent.ACTION_UP, keyCode, 0, 0,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                    KeyEvent.FLAG_SOFT_KEYBOARD));
        }

        private CharSequence limitReplaceTextByMaxLength(CharSequence text,
                int numReplaced) {
            if (mMaxLength > 0) {
                Editable editable = getEditable();
                int maxReplace = mMaxLength - editable.length() + numReplaced;
                if (maxReplace < text.length()) {
                    maxReplace = Math.max(maxReplace, 0);
                    // New length is greater than the maximum. trim it down.
                    text = text.subSequence(0, maxReplace);
                }
            }
            return text;
        }

        private void restartInput() {
            InputMethodManager imm = InputMethodManager.peekInstance();
            if (imm != null) {
                // Since the text has changed, do not allow the IME to replace the
                // existing text as though it were a completion.
                imm.restartInput(mWebView);
            }
        }
    }

    private class PastePopupWindow extends PopupWindow implements View.OnClickListener {
        private ViewGroup mContentView;
        private TextView mPasteTextView;

        public PastePopupWindow() {
            super(mContext, null,
                    com.android.internal.R.attr.textSelectHandleWindowStyle);
            setClippingEnabled(true);
            LinearLayout linearLayout = new LinearLayout(mContext);
            linearLayout.setOrientation(LinearLayout.HORIZONTAL);
            mContentView = linearLayout;
            mContentView.setBackgroundResource(
                    com.android.internal.R.drawable.text_edit_paste_window);

            LayoutInflater inflater = (LayoutInflater)mContext.
                    getSystemService(Context.LAYOUT_INFLATER_SERVICE);

            ViewGroup.LayoutParams wrapContent = new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);

            mPasteTextView = (TextView) inflater.inflate(
                    com.android.internal.R.layout.text_edit_action_popup_text, null);
            mPasteTextView.setLayoutParams(wrapContent);
            mContentView.addView(mPasteTextView);
            mPasteTextView.setText(com.android.internal.R.string.paste);
            mPasteTextView.setOnClickListener(this);
            this.setContentView(mContentView);
        }

        public void show(Point cursorBottom, Point cursorTop,
                int windowLeft, int windowTop) {
            measureContent();

            int width = mContentView.getMeasuredWidth();
            int height = mContentView.getMeasuredHeight();
            int y = cursorTop.y - height;
            int x = cursorTop.x - (width / 2);
            if (y < windowTop) {
                // There's not enough room vertically, move it below the
                // handle.
                ensureSelectionHandles();
                y = cursorBottom.y + mSelectHandleCenter.getIntrinsicHeight();
                x = cursorBottom.x - (width / 2);
            }
            if (x < windowLeft) {
                x = windowLeft;
            }
            if (!isShowing()) {
                showAtLocation(mWebView, Gravity.NO_GRAVITY, x, y);
            }
            update(x, y, width, height);
        }

        public void hide() {
            dismiss();
        }

        @Override
        public void onClick(View view) {
            pasteFromClipboard();
            selectionDone();
        }

        protected void measureContent() {
            final DisplayMetrics displayMetrics = mContext.getResources().getDisplayMetrics();
            mContentView.measure(
                    View.MeasureSpec.makeMeasureSpec(displayMetrics.widthPixels,
                            View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(displayMetrics.heightPixels,
                            View.MeasureSpec.AT_MOST));
        }
    }

    // if AUTO_REDRAW_HACK is true, then the CALL key will toggle redrawing
    // the screen all-the-time. Good for profiling our drawing code
    static private final boolean AUTO_REDRAW_HACK = false;

    // The rate at which edit text is scrolled in content pixels per millisecond
    static private final float TEXT_SCROLL_RATE = 0.01f;

    // The presumed scroll rate for the first scroll of edit text
    static private final long TEXT_SCROLL_FIRST_SCROLL_MS = 16;

    // Buffer pixels of the caret rectangle when moving edit text into view
    // after resize.
    static private final int EDIT_RECT_BUFFER = 10;

    static private final long SELECTION_HANDLE_ANIMATION_MS = 150;

    // true means redraw the screen all-the-time. Only with AUTO_REDRAW_HACK
    private boolean mAutoRedraw;

    // Reference to the AlertDialog displayed by InvokeListBox.
    // It's used to dismiss the dialog in destroy if not done before.
    private AlertDialog mListBoxDialog = null;

    // Reference to the save password dialog so it can be dimissed in
    // destroy if not done before.
    private AlertDialog mSavePasswordDialog = null;

    static final String LOGTAG = "webview";

    private ZoomManager mZoomManager;

    private final Rect mInvScreenRect = new Rect();
    private final Rect mScreenRect = new Rect();
    private final RectF mVisibleContentRect = new RectF();
    private boolean mIsWebViewVisible = true;
    WebViewInputConnection mInputConnection = null;
    private int mFieldPointer;
    private PastePopupWindow mPasteWindow;
    private AutoCompletePopup mAutoCompletePopup;
    Rect mEditTextContentBounds = new Rect();
    Rect mEditTextContent = new Rect();
    int mEditTextLayerId;
    boolean mIsEditingText = false;
    ArrayList<Message> mBatchedTextChanges = new ArrayList<Message>();
    boolean mIsBatchingTextChanges = false;
    private long mLastEditScroll = 0;

    private static class OnTrimMemoryListener implements ComponentCallbacks2 {
        private static OnTrimMemoryListener sInstance = null;

        static void init(Context c) {
            if (sInstance == null) {
                sInstance = new OnTrimMemoryListener(c.getApplicationContext());
            }
        }

        private OnTrimMemoryListener(Context c) {
            c.registerComponentCallbacks(this);
        }

        @Override
        public void onConfigurationChanged(Configuration newConfig) {
            // Ignore
        }

        @Override
        public void onLowMemory() {
            // Ignore
        }

        @Override
        public void onTrimMemory(int level) {
            if (DebugFlags.WEB_VIEW) {
                Log.d("WebView", "onTrimMemory: " + level);
            }
            // When framework reset EGL context during high memory pressure, all
            // the existing GL resources for the html5 video will be destroyed
            // at native side.
            // Here we just need to clean up the Surface Texture which is static.
            if (level > TRIM_MEMORY_UI_HIDDEN) {
                HTML5VideoInline.cleanupSurfaceTexture();
                HTML5VideoView.release();
            }
            WebViewClassic.nativeOnTrimMemory(level);
        }
    }

    // A final CallbackProxy shared by WebViewCore and BrowserFrame.
    private CallbackProxy mCallbackProxy;

    private WebViewDatabaseClassic mDatabase;

    // SSL certificate for the main top-level page (if secure)
    private SslCertificate mCertificate;

    // Native WebView pointer that is 0 until the native object has been
    // created.
    private int mNativeClass;
    // This would be final but it needs to be set to null when the WebView is
    // destroyed.
    private WebViewCore mWebViewCore;
    // Handler for dispatching UI messages.
    /* package */ final Handler mPrivateHandler = new PrivateHandler();
    // Used to ignore changes to webkit text that arrives to the UI side after
    // more key events.
    private int mTextGeneration;

    /* package */ void incrementTextGeneration() { mTextGeneration++; }

    // Used by WebViewCore to create child views.
    /* package */ ViewManager mViewManager;

    // Used to display in full screen mode
    PluginFullScreenHolder mFullScreenHolder;

    /**
     * Position of the last touch event in pixels.
     * Use integer to prevent loss of dragging delta calculation accuracy;
     * which was done in float and converted to integer, and resulted in gradual
     * and compounding touch position and view dragging mismatch.
     */
    private int mLastTouchX;
    private int mLastTouchY;
    private int mStartTouchX;
    private int mStartTouchY;
    private float mAverageAngle;

    /**
     * Time of the last touch event.
     */
    private long mLastTouchTime;

    /**
     * Time of the last time sending touch event to WebViewCore
     */
    private long mLastSentTouchTime;

    /**
     * The minimum elapsed time before sending another ACTION_MOVE event to
     * WebViewCore. This really should be tuned for each type of the devices.
     * For example in Google Map api test case, it takes Dream device at least
     * 150ms to do a full cycle in the WebViewCore by processing a touch event,
     * triggering the layout and drawing the picture. While the same process
     * takes 60+ms on the current high speed device. If we make
     * TOUCH_SENT_INTERVAL too small, there will be multiple touch events sent
     * to WebViewCore queue and the real layout and draw events will be pushed
     * to further, which slows down the refresh rate. Choose 50 to favor the
     * current high speed devices. For Dream like devices, 100 is a better
     * choice. Maybe make this in the buildspec later.
     * (Update 12/14/2010: changed to 0 since current device should be able to
     * handle the raw events and Map team voted to have the raw events too.
     */
    private static final int TOUCH_SENT_INTERVAL = 0;
    private int mCurrentTouchInterval = TOUCH_SENT_INTERVAL;

    /**
     * Helper class to get velocity for fling
     */
    VelocityTracker mVelocityTracker;
    private int mMaximumFling;
    private float mLastVelocity;
    private float mLastVelX;
    private float mLastVelY;

    // The id of the native layer being scrolled.
    private int mCurrentScrollingLayerId;
    private Rect mScrollingLayerRect = new Rect();

    // only trigger accelerated fling if the new velocity is at least
    // MINIMUM_VELOCITY_RATIO_FOR_ACCELERATION times of the previous velocity
    private static final float MINIMUM_VELOCITY_RATIO_FOR_ACCELERATION = 0.2f;

    /**
     * Touch mode
     * TODO: Some of this is now unnecessary as it is handled by
     * WebInputTouchDispatcher (such as click, long press, and double tap).
     */
    private int mTouchMode = TOUCH_DONE_MODE;
    private static final int TOUCH_INIT_MODE = 1;
    private static final int TOUCH_DRAG_START_MODE = 2;
    private static final int TOUCH_DRAG_MODE = 3;
    private static final int TOUCH_SHORTPRESS_START_MODE = 4;
    private static final int TOUCH_SHORTPRESS_MODE = 5;
    private static final int TOUCH_DOUBLE_TAP_MODE = 6;
    private static final int TOUCH_DONE_MODE = 7;
    private static final int TOUCH_PINCH_DRAG = 8;
    private static final int TOUCH_DRAG_LAYER_MODE = 9;
    private static final int TOUCH_DRAG_TEXT_MODE = 10;

    // true when the touch movement exceeds the slop
    private boolean mConfirmMove;
    private boolean mTouchInEditText;

    // Whether or not to draw the cursor ring.
    private boolean mDrawCursorRing = true;

    // true if onPause has been called (and not onResume)
    private boolean mIsPaused;

    private HitTestResult mInitialHitTestResult;
    private WebKitHitTest mFocusedNode;

    /**
     * Customizable constant
     */
    // pre-computed square of ViewConfiguration.getScaledTouchSlop()
    private int mTouchSlopSquare;
    // pre-computed square of ViewConfiguration.getScaledDoubleTapSlop()
    private int mDoubleTapSlopSquare;
    // pre-computed density adjusted navigation slop
    private int mNavSlop;
    // This should be ViewConfiguration.getTapTimeout()
    // But system time out is 100ms, which is too short for the browser.
    // In the browser, if it switches out of tap too soon, jump tap won't work.
    // In addition, a double tap on a trackpad will always have a duration of
    // 300ms, so this value must be at least that (otherwise we will timeout the
    // first tap and convert it to a long press).
    private static final int TAP_TIMEOUT = 300;
    // This should be ViewConfiguration.getLongPressTimeout()
    // But system time out is 500ms, which is too short for the browser.
    // With a short timeout, it's difficult to treat trigger a short press.
    private static final int LONG_PRESS_TIMEOUT = 1000;
    // needed to avoid flinging after a pause of no movement
    private static final int MIN_FLING_TIME = 250;
    // draw unfiltered after drag is held without movement
    private static final int MOTIONLESS_TIME = 100;
    // The amount of content to overlap between two screens when going through
    // pages with the space bar, in pixels.
    private static final int PAGE_SCROLL_OVERLAP = 24;

    /**
     * These prevent calling requestLayout if either dimension is fixed. This
     * depends on the layout parameters and the measure specs.
     */
    boolean mWidthCanMeasure;
    boolean mHeightCanMeasure;

    // Remember the last dimensions we sent to the native side so we can avoid
    // sending the same dimensions more than once.
    int mLastWidthSent;
    int mLastHeightSent;
    // Since view height sent to webkit could be fixed to avoid relayout, this
    // value records the last sent actual view height.
    int mLastActualHeightSent;

    private int mContentWidth;   // cache of value from WebViewCore
    private int mContentHeight;  // cache of value from WebViewCore

    // Need to have the separate control for horizontal and vertical scrollbar
    // style than the View's single scrollbar style
    private boolean mOverlayHorizontalScrollbar = true;
    private boolean mOverlayVerticalScrollbar = false;

    // our standard speed. this way small distances will be traversed in less
    // time than large distances, but we cap the duration, so that very large
    // distances won't take too long to get there.
    private static final int STD_SPEED = 480;  // pixels per second
    // time for the longest scroll animation
    private static final int MAX_DURATION = 750;   // milliseconds

    // Used by OverScrollGlow
    OverScroller mScroller;
    Scroller mEditTextScroller;

    private boolean mInOverScrollMode = false;
    private static Paint mOverScrollBackground;
    private static Paint mOverScrollBorder;

    private boolean mWrapContent;
    private static final int MOTIONLESS_FALSE           = 0;
    private static final int MOTIONLESS_PENDING         = 1;
    private static final int MOTIONLESS_TRUE            = 2;
    private static final int MOTIONLESS_IGNORE          = 3;
    private int mHeldMotionless;

    // Lazily-instantiated instance for injecting accessibility.
    private AccessibilityInjector mAccessibilityInjector;

    /**
     * How long the caret handle will last without being touched.
     */
    private static final long CARET_HANDLE_STAMINA_MS = 3000;

    private Drawable mSelectHandleLeft;
    private Drawable mSelectHandleRight;
    private Drawable mSelectHandleCenter;
    private Point mSelectOffset;
    private Point mSelectCursorBase = new Point();
    private Rect mSelectHandleBaseBounds = new Rect();
    private int mSelectCursorBaseLayerId;
    private QuadF mSelectCursorBaseTextQuad = new QuadF();
    private Point mSelectCursorExtent = new Point();
    private Rect mSelectHandleExtentBounds = new Rect();
    private int mSelectCursorExtentLayerId;
    private QuadF mSelectCursorExtentTextQuad = new QuadF();
    private Point mSelectDraggingCursor;
    private QuadF mSelectDraggingTextQuad;
    private boolean mIsCaretSelection;
    static final int HANDLE_ID_BASE = 0;
    static final int HANDLE_ID_EXTENT = 1;

    // the color used to highlight the touch rectangles
    static final int HIGHLIGHT_COLOR = 0x6633b5e5;
    // the region indicating where the user touched on the screen
    private Region mTouchHighlightRegion = new Region();
    // the paint for the touch highlight
    private Paint mTouchHightlightPaint = new Paint();
    // debug only
    private static final boolean DEBUG_TOUCH_HIGHLIGHT = true;
    private static final int TOUCH_HIGHLIGHT_ELAPSE_TIME = 2000;
    private Paint mTouchCrossHairColor;
    private int mTouchHighlightX;
    private int mTouchHighlightY;
    private boolean mShowTapHighlight;

    // Basically this proxy is used to tell the Video to update layer tree at
    // SetBaseLayer time and to pause when WebView paused.
    private HTML5VideoViewProxy mHTML5VideoViewProxy;

    // If we are using a set picture, don't send view updates to webkit
    private boolean mBlockWebkitViewMessages = false;

    // cached value used to determine if we need to switch drawing models
    private boolean mHardwareAccelSkia = false;

    /*
     * Private message ids
     */
    private static final int REMEMBER_PASSWORD          = 1;
    private static final int NEVER_REMEMBER_PASSWORD    = 2;
    private static final int SWITCH_TO_SHORTPRESS       = 3;
    private static final int SWITCH_TO_LONGPRESS        = 4;
    private static final int RELEASE_SINGLE_TAP         = 5;
    private static final int REQUEST_FORM_DATA          = 6;
    private static final int DRAG_HELD_MOTIONLESS       = 8;
    private static final int PREVENT_DEFAULT_TIMEOUT    = 10;
    private static final int SCROLL_SELECT_TEXT         = 11;


    private static final int FIRST_PRIVATE_MSG_ID = REMEMBER_PASSWORD;
    private static final int LAST_PRIVATE_MSG_ID = SCROLL_SELECT_TEXT;

    /*
     * Package message ids
     */
    static final int SCROLL_TO_MSG_ID                   = 101;
    static final int NEW_PICTURE_MSG_ID                 = 105;
    static final int WEBCORE_INITIALIZED_MSG_ID         = 107;
    static final int UPDATE_TEXTFIELD_TEXT_MSG_ID       = 108;
    static final int UPDATE_ZOOM_RANGE                  = 109;
    static final int TAKE_FOCUS                         = 110;
    static final int CLEAR_TEXT_ENTRY                   = 111;
    static final int UPDATE_TEXT_SELECTION_MSG_ID       = 112;
    static final int SHOW_RECT_MSG_ID                   = 113;
    static final int LONG_PRESS_CENTER                  = 114;
    static final int PREVENT_TOUCH_ID                   = 115;
    static final int WEBCORE_NEED_TOUCH_EVENTS          = 116;
    // obj=Rect in doc coordinates
    static final int INVAL_RECT_MSG_ID                  = 117;
    static final int REQUEST_KEYBOARD                   = 118;
    static final int SHOW_FULLSCREEN                    = 120;
    static final int HIDE_FULLSCREEN                    = 121;
    static final int UPDATE_MATCH_COUNT                 = 126;
    static final int CENTER_FIT_RECT                    = 127;
    static final int SET_SCROLLBAR_MODES                = 129;
    static final int HIT_TEST_RESULT                    = 130;
    static final int SAVE_WEBARCHIVE_FINISHED           = 131;
    static final int SET_AUTOFILLABLE                   = 132;
    static final int AUTOFILL_COMPLETE                  = 133;
    static final int SCREEN_ON                          = 134;
    static final int UPDATE_ZOOM_DENSITY                = 135;
    static final int EXIT_FULLSCREEN_VIDEO              = 136;
    static final int COPY_TO_CLIPBOARD                  = 137;
    static final int INIT_EDIT_FIELD                    = 138;
    static final int REPLACE_TEXT                       = 139;
    static final int CLEAR_CARET_HANDLE                 = 140;
    static final int KEY_PRESS                          = 141;
    static final int RELOCATE_AUTO_COMPLETE_POPUP       = 142;
    static final int FOCUS_NODE_CHANGED                 = 143;
    static final int AUTOFILL_FORM                      = 144;
    static final int SCROLL_EDIT_TEXT                   = 145;
    static final int EDIT_TEXT_SIZE_CHANGED             = 146;
    static final int SHOW_CARET_HANDLE                  = 147;
    static final int UPDATE_CONTENT_BOUNDS              = 148;
    static final int SCROLL_HANDLE_INTO_VIEW            = 149;

    private static final int FIRST_PACKAGE_MSG_ID = SCROLL_TO_MSG_ID;
    private static final int LAST_PACKAGE_MSG_ID = HIT_TEST_RESULT;

    static final String[] HandlerPrivateDebugString = {
        "REMEMBER_PASSWORD", //              = 1;
        "NEVER_REMEMBER_PASSWORD", //        = 2;
        "SWITCH_TO_SHORTPRESS", //           = 3;
        "SWITCH_TO_LONGPRESS", //            = 4;
        "RELEASE_SINGLE_TAP", //             = 5;
        "REQUEST_FORM_DATA", //              = 6;
        "RESUME_WEBCORE_PRIORITY", //        = 7;
        "DRAG_HELD_MOTIONLESS", //           = 8;
        "", //             = 9;
        "PREVENT_DEFAULT_TIMEOUT", //        = 10;
        "SCROLL_SELECT_TEXT" //              = 11;
    };

    static final String[] HandlerPackageDebugString = {
        "SCROLL_TO_MSG_ID", //               = 101;
        "102", //                            = 102;
        "103", //                            = 103;
        "104", //                            = 104;
        "NEW_PICTURE_MSG_ID", //             = 105;
        "UPDATE_TEXT_ENTRY_MSG_ID", //       = 106;
        "WEBCORE_INITIALIZED_MSG_ID", //     = 107;
        "UPDATE_TEXTFIELD_TEXT_MSG_ID", //   = 108;
        "UPDATE_ZOOM_RANGE", //              = 109;
        "UNHANDLED_NAV_KEY", //              = 110;
        "CLEAR_TEXT_ENTRY", //               = 111;
        "UPDATE_TEXT_SELECTION_MSG_ID", //   = 112;
        "SHOW_RECT_MSG_ID", //               = 113;
        "LONG_PRESS_CENTER", //              = 114;
        "PREVENT_TOUCH_ID", //               = 115;
        "WEBCORE_NEED_TOUCH_EVENTS", //      = 116;
        "INVAL_RECT_MSG_ID", //              = 117;
        "REQUEST_KEYBOARD", //               = 118;
        "DO_MOTION_UP", //                   = 119;
        "SHOW_FULLSCREEN", //                = 120;
        "HIDE_FULLSCREEN", //                = 121;
        "DOM_FOCUS_CHANGED", //              = 122;
        "REPLACE_BASE_CONTENT", //           = 123;
        "RETURN_LABEL", //                   = 125;
        "UPDATE_MATCH_COUNT", //             = 126;
        "CENTER_FIT_RECT", //                = 127;
        "REQUEST_KEYBOARD_WITH_SELECTION_MSG_ID", // = 128;
        "SET_SCROLLBAR_MODES", //            = 129;
        "SELECTION_STRING_CHANGED", //       = 130;
        "SET_TOUCH_HIGHLIGHT_RECTS", //      = 131;
        "SAVE_WEBARCHIVE_FINISHED", //       = 132;
        "SET_AUTOFILLABLE", //               = 133;
        "AUTOFILL_COMPLETE", //              = 134;
        "SELECT_AT", //                      = 135;
        "SCREEN_ON", //                      = 136;
        "ENTER_FULLSCREEN_VIDEO", //         = 137;
        "UPDATE_SELECTION", //               = 138;
        "UPDATE_ZOOM_DENSITY" //             = 139;
    };

    // If the site doesn't use the viewport meta tag to specify the viewport,
    // use DEFAULT_VIEWPORT_WIDTH as the default viewport width
    static final int DEFAULT_VIEWPORT_WIDTH = 980;

    // normally we try to fit the content to the minimum preferred width
    // calculated by the Webkit. To avoid the bad behavior when some site's
    // minimum preferred width keeps growing when changing the viewport width or
    // the minimum preferred width is huge, an upper limit is needed.
    static int sMaxViewportWidth = DEFAULT_VIEWPORT_WIDTH;

    // initial scale in percent. 0 means using default.
    private int mInitialScaleInPercent = 0;

    // Whether or not a scroll event should be sent to webkit.  This is only set
    // to false when restoring the scroll position.
    private boolean mSendScrollEvent = true;

    private int mSnapScrollMode = SNAP_NONE;
    private static final int SNAP_NONE = 0;
    private static final int SNAP_LOCK = 1; // not a separate state
    private static final int SNAP_X = 2; // may be combined with SNAP_LOCK
    private static final int SNAP_Y = 4; // may be combined with SNAP_LOCK
    private boolean mSnapPositive;

    // keep these in sync with their counterparts in WebView.cpp
    private static final int DRAW_EXTRAS_NONE = 0;
    private static final int DRAW_EXTRAS_SELECTION = 1;
    private static final int DRAW_EXTRAS_CURSOR_RING = 2;

    // keep this in sync with WebCore:ScrollbarMode in WebKit
    private static final int SCROLLBAR_AUTO = 0;
    private static final int SCROLLBAR_ALWAYSOFF = 1;
    // as we auto fade scrollbar, this is ignored.
    private static final int SCROLLBAR_ALWAYSON = 2;
    private int mHorizontalScrollBarMode = SCROLLBAR_AUTO;
    private int mVerticalScrollBarMode = SCROLLBAR_AUTO;

    /**
     * Max distance to overscroll by in pixels.
     * This how far content can be pulled beyond its normal bounds by the user.
     */
    private int mOverscrollDistance;

    /**
     * Max distance to overfling by in pixels.
     * This is how far flinged content can move beyond the end of its normal bounds.
     */
    private int mOverflingDistance;

    private OverScrollGlow mOverScrollGlow;

    // Used to match key downs and key ups
    private Vector<Integer> mKeysPressed;

    /* package */ static boolean mLogEvent = true;

    // for event log
    private long mLastTouchUpTime = 0;

    private WebViewCore.AutoFillData mAutoFillData;

    private static boolean sNotificationsEnabled = true;

    /**
     * URI scheme for telephone number
     */
    public static final String SCHEME_TEL = "tel:";
    /**
     * URI scheme for email address
     */
    public static final String SCHEME_MAILTO = "mailto:";
    /**
     * URI scheme for map address
     */
    public static final String SCHEME_GEO = "geo:0,0?q=";

    private int mBackgroundColor = Color.WHITE;

    private static final long SELECT_SCROLL_INTERVAL = 1000 / 60; // 60 / second
    private int mAutoScrollX = 0;
    private int mAutoScrollY = 0;
    private int mMinAutoScrollX = 0;
    private int mMaxAutoScrollX = 0;
    private int mMinAutoScrollY = 0;
    private int mMaxAutoScrollY = 0;
    private Rect mScrollingLayerBounds = new Rect();
    private boolean mSentAutoScrollMessage = false;

    // used for serializing asynchronously handled touch events.
    private WebViewInputDispatcher mInputDispatcher;

    // Used to track whether picture updating was paused due to a window focus change.
    private boolean mPictureUpdatePausedForFocusChange = false;

    // Used to notify listeners of a new picture.
    private PictureListener mPictureListener;

    // Used to notify listeners about find-on-page results.
    private WebView.FindListener mFindListener;

    // Used to prevent resending save password message
    private Message mResumeMsg;

    /**
     * Refer to {@link WebView#requestFocusNodeHref(Message)} for more information
     */
    static class FocusNodeHref {
        static final String TITLE = "title";
        static final String URL = "url";
        static final String SRC = "src";
    }

    public WebViewClassic(WebView webView, WebView.PrivateAccess privateAccess) {
        mWebView = webView;
        mWebViewPrivate = privateAccess;
        mContext = webView.getContext();
    }

    /**
     * See {@link WebViewProvider#init(Map, boolean)}
     */
    @Override
    public void init(Map<String, Object> javaScriptInterfaces, boolean privateBrowsing) {
        Context context = mContext;

        // Used by the chrome stack to find application paths
        JniUtil.setContext(context);

        mCallbackProxy = new CallbackProxy(context, this);
        mViewManager = new ViewManager(this);
        L10nUtils.setApplicationContext(context.getApplicationContext());
        mWebViewCore = new WebViewCore(context, this, mCallbackProxy, javaScriptInterfaces);
        mDatabase = WebViewDatabaseClassic.getInstance(context);
        mScroller = new OverScroller(context, null, 0, 0, false); //TODO Use OverScroller's flywheel
        mZoomManager = new ZoomManager(this, mCallbackProxy);

        /* The init method must follow the creation of certain member variables,
         * such as the mZoomManager.
         */
        init();
        setupPackageListener(context);
        setupProxyListener(context);
        setupTrustStorageListener(context);
        updateMultiTouchSupport(context);

        if (privateBrowsing) {
            startPrivateBrowsing();
        }

        mAutoFillData = new WebViewCore.AutoFillData();
        mEditTextScroller = new Scroller(context);

        // Calculate channel distance
        calculateChannelDistance(context);
    }

    /**
     * Calculate sChannelDistance based on the screen information.
     * @param context A Context object used to access application assets.
     */
    private void calculateChannelDistance(Context context) {
        // The channel distance is adjusted for density and screen size
        final DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        final double screenSize = Math.hypot((double)(metrics.widthPixels/metrics.densityDpi),
                (double)(metrics.heightPixels/metrics.densityDpi));
        if (screenSize < 3.0) {
            sChannelDistance = 16;
        } else if (screenSize < 5.0) {
            sChannelDistance = 22;
        } else if (screenSize < 7.0) {
            sChannelDistance = 28;
        } else {
            sChannelDistance = 34;
        }
        sChannelDistance = (int)(sChannelDistance * metrics.density);
        if (sChannelDistance < 16) sChannelDistance = 16;

        if (DebugFlags.WEB_VIEW) {
            Log.v(LOGTAG, "sChannelDistance : " + sChannelDistance
                    + ", density : " + metrics.density
                    + ", screenSize : " + screenSize
                    + ", metrics.heightPixels : " + metrics.heightPixels
                    + ", metrics.widthPixels : " + metrics.widthPixels
                    + ", metrics.densityDpi : " + metrics.densityDpi);
        }
    }

    // WebViewProvider bindings

    static class Factory implements WebViewFactoryProvider,  WebViewFactoryProvider.Statics {
        @Override
        public String findAddress(String addr) {
            return WebViewClassic.findAddress(addr);
        }
        @Override
        public void setPlatformNotificationsEnabled(boolean enable) {
            if (enable) {
                WebViewClassic.enablePlatformNotifications();
            } else {
                WebViewClassic.disablePlatformNotifications();
            }
        }

        @Override
        public Statics getStatics() { return this; }

        @Override
        public WebViewProvider createWebView(WebView webView, WebView.PrivateAccess privateAccess) {
            return new WebViewClassic(webView, privateAccess);
        }

        @Override
        public GeolocationPermissions getGeolocationPermissions() {
            return GeolocationPermissionsClassic.getInstance();
        }

        @Override
        public CookieManager getCookieManager() {
            return CookieManagerClassic.getInstance();
        }

        @Override
        public WebIconDatabase getWebIconDatabase() {
            return WebIconDatabaseClassic.getInstance();
        }

        @Override
        public WebStorage getWebStorage() {
            return WebStorageClassic.getInstance();
        }

        @Override
        public WebViewDatabase getWebViewDatabase(Context context) {
            return WebViewDatabaseClassic.getInstance(context);
        }

        @Override
        public String getDefaultUserAgent(Context context) {
            return WebSettingsClassic.getDefaultUserAgentForLocale(context,
                    Locale.getDefault());
        }
    }

    private void onHandleUiEvent(MotionEvent event, int eventType, int flags) {
        switch (eventType) {
        case WebViewInputDispatcher.EVENT_TYPE_LONG_PRESS:
            HitTestResult hitTest = getHitTestResult();
            if (hitTest != null) {
                mWebView.performLongClick();
            }
            break;
        case WebViewInputDispatcher.EVENT_TYPE_DOUBLE_TAP:
            mZoomManager.handleDoubleTap(event.getX(), event.getY());
            break;
        case WebViewInputDispatcher.EVENT_TYPE_TOUCH:
            onHandleUiTouchEvent(event);
            break;
        case WebViewInputDispatcher.EVENT_TYPE_CLICK:
            if (mFocusedNode != null && mFocusedNode.mIntentUrl != null) {
                mWebView.playSoundEffect(SoundEffectConstants.CLICK);
                overrideLoading(mFocusedNode.mIntentUrl);
            }
            break;
        }
    }

    private void onHandleUiTouchEvent(MotionEvent ev) {
        final ScaleGestureDetector detector =
                mZoomManager.getScaleGestureDetector();

        int action = ev.getActionMasked();
        final boolean pointerUp = action == MotionEvent.ACTION_POINTER_UP;
        final boolean configChanged =
            action == MotionEvent.ACTION_POINTER_UP ||
            action == MotionEvent.ACTION_POINTER_DOWN;
        final int skipIndex = pointerUp ? ev.getActionIndex() : -1;

        // Determine focal point
        float sumX = 0, sumY = 0;
        final int count = ev.getPointerCount();
        for (int i = 0; i < count; i++) {
            if (skipIndex == i) continue;
            sumX += ev.getX(i);
            sumY += ev.getY(i);
        }
        final int div = pointerUp ? count - 1 : count;
        float x = sumX / div;
        float y = sumY / div;

        if (configChanged) {
            mLastTouchX = Math.round(x);
            mLastTouchY = Math.round(y);
            mLastTouchTime = ev.getEventTime();
            mWebView.cancelLongPress();
            mPrivateHandler.removeMessages(SWITCH_TO_LONGPRESS);
        }

        if (detector != null) {
            detector.onTouchEvent(ev);
            if (detector.isInProgress()) {
                mLastTouchTime = ev.getEventTime();

                if (!mZoomManager.supportsPanDuringZoom()) {
                    return;
                }
                mTouchMode = TOUCH_DRAG_MODE;
                if (mVelocityTracker == null) {
                    mVelocityTracker = VelocityTracker.obtain();
                }
            }
        }

        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            cancelTouch();
            action = MotionEvent.ACTION_DOWN;
        } else if (action == MotionEvent.ACTION_MOVE) {
            // negative x or y indicate it is on the edge, skip it.
            if (x < 0 || y < 0) {
                return;
            }
        }

        handleTouchEventCommon(ev, action, Math.round(x), Math.round(y));
    }

    // The webview that is bound to this WebViewClassic instance. Primarily needed for supplying
    // as the first param in the WebViewClient and WebChromeClient callbacks.
    final private WebView mWebView;
    // Callback interface, provides priviledged access into the WebView instance.
    final private WebView.PrivateAccess mWebViewPrivate;
    // Cached reference to mWebView.getContext(), for convenience.
    final private Context mContext;

    /**
     * @return The webview proxy that this classic webview is bound to.
     */
    public WebView getWebView() {
        return mWebView;
    }

    @Override
    public ViewDelegate getViewDelegate() {
        return this;
    }

    @Override
    public ScrollDelegate getScrollDelegate() {
        return this;
    }

    public static WebViewClassic fromWebView(WebView webView) {
        return webView == null ? null : (WebViewClassic) webView.getWebViewProvider();
    }

    // Accessors, purely for convenience (and to reduce code churn during webview proxy migration).
    int getScrollX() {
        return mWebView.getScrollX();
    }

    int getScrollY() {
        return mWebView.getScrollY();
    }

    int getWidth() {
        return mWebView.getWidth();
    }

    int getHeight() {
        return mWebView.getHeight();
    }

    Context getContext() {
        return mContext;
    }

    void invalidate() {
        mWebView.invalidate();
    }

    // Setters for the Scroll X & Y, without invoking the onScrollChanged etc code paths.
    void setScrollXRaw(int mScrollX) {
        mWebViewPrivate.setScrollXRaw(mScrollX);
    }

    void setScrollYRaw(int mScrollY) {
        mWebViewPrivate.setScrollYRaw(mScrollY);
    }

    private static class TrustStorageListener extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(KeyChain.ACTION_STORAGE_CHANGED)) {
                handleCertTrustChanged();
            }
        }
    }
    private static TrustStorageListener sTrustStorageListener;

    /**
     * Handles update to the trust storage.
     */
    private static void handleCertTrustChanged() {
        // send a message for indicating trust storage change
        WebViewCore.sendStaticMessage(EventHub.TRUST_STORAGE_UPDATED, null);
    }

    /*
     * @param context This method expects this to be a valid context.
     */
    private static void setupTrustStorageListener(Context context) {
        if (sTrustStorageListener != null ) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(KeyChain.ACTION_STORAGE_CHANGED);
        sTrustStorageListener = new TrustStorageListener();
        Intent current =
            context.getApplicationContext().registerReceiver(sTrustStorageListener, filter);
        if (current != null) {
            handleCertTrustChanged();
        }
    }

    private static class ProxyReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Proxy.PROXY_CHANGE_ACTION)) {
                handleProxyBroadcast(intent);
            }
        }
    }

    /*
     * Receiver for PROXY_CHANGE_ACTION, will be null when it is not added handling broadcasts.
     */
    private static ProxyReceiver sProxyReceiver;

    /*
     * @param context This method expects this to be a valid context
     */
    private static synchronized void setupProxyListener(Context context) {
        if (sProxyReceiver != null || sNotificationsEnabled == false) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(Proxy.PROXY_CHANGE_ACTION);
        sProxyReceiver = new ProxyReceiver();
        Intent currentProxy = context.getApplicationContext().registerReceiver(
                sProxyReceiver, filter);
        if (currentProxy != null) {
            handleProxyBroadcast(currentProxy);
        }
    }

    /*
     * @param context This method expects this to be a valid context
     */
    private static synchronized void disableProxyListener(Context context) {
        if (sProxyReceiver == null)
            return;

        context.getApplicationContext().unregisterReceiver(sProxyReceiver);
        sProxyReceiver = null;
    }

    private static void handleProxyBroadcast(Intent intent) {
        ProxyProperties proxyProperties = (ProxyProperties)intent.getExtra(Proxy.EXTRA_PROXY_INFO);
        if (proxyProperties == null || proxyProperties.getHost() == null) {
            WebViewCore.sendStaticMessage(EventHub.PROXY_CHANGED, null);
            return;
        }
        WebViewCore.sendStaticMessage(EventHub.PROXY_CHANGED, proxyProperties);
    }

    /*
     * A variable to track if there is a receiver added for ACTION_PACKAGE_ADDED
     * or ACTION_PACKAGE_REMOVED.
     */
    private static boolean sPackageInstallationReceiverAdded = false;

    /*
     * A set of Google packages we monitor for the
     * navigator.isApplicationInstalled() API. Add additional packages as
     * needed.
     */
    private static Set<String> sGoogleApps;
    static {
        sGoogleApps = new HashSet<String>();
        sGoogleApps.add("com.google.android.youtube");
    }

    private static class PackageListener extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            final String packageName = intent.getData().getSchemeSpecificPart();
            final boolean replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
            if (Intent.ACTION_PACKAGE_REMOVED.equals(action) && replacing) {
                // if it is replacing, refreshPlugins() when adding
                return;
            }

            if (sGoogleApps.contains(packageName)) {
                if (Intent.ACTION_PACKAGE_ADDED.equals(action)) {
                    WebViewCore.sendStaticMessage(EventHub.ADD_PACKAGE_NAME, packageName);
                } else {
                    WebViewCore.sendStaticMessage(EventHub.REMOVE_PACKAGE_NAME, packageName);
                }
            }

            PluginManager pm = PluginManager.getInstance(context);
            if (pm.containsPluginPermissionAndSignatures(packageName)) {
                pm.refreshPlugins(Intent.ACTION_PACKAGE_ADDED.equals(action));
            }
        }
    }

    private void setupPackageListener(Context context) {

        /*
         * we must synchronize the instance check and the creation of the
         * receiver to ensure that only ONE receiver exists for all WebView
         * instances.
         */
        synchronized (WebViewClassic.class) {

            // if the receiver already exists then we do not need to register it
            // again
            if (sPackageInstallationReceiverAdded) {
                return;
            }

            IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
            filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
            filter.addDataScheme("package");
            BroadcastReceiver packageListener = new PackageListener();
            context.getApplicationContext().registerReceiver(packageListener, filter);
            sPackageInstallationReceiverAdded = true;
        }

        // check if any of the monitored apps are already installed
        AsyncTask<Void, Void, Set<String>> task = new AsyncTask<Void, Void, Set<String>>() {

            @Override
            protected Set<String> doInBackground(Void... unused) {
                Set<String> installedPackages = new HashSet<String>();
                PackageManager pm = mContext.getPackageManager();
                for (String name : sGoogleApps) {
                    try {
                        pm.getPackageInfo(name,
                                PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES);
                        installedPackages.add(name);
                    } catch (PackageManager.NameNotFoundException e) {
                        // package not found
                    }
                }
                return installedPackages;
            }

            // Executes on the UI thread
            @Override
            protected void onPostExecute(Set<String> installedPackages) {
                if (mWebViewCore != null) {
                    mWebViewCore.sendMessage(EventHub.ADD_PACKAGE_NAMES, installedPackages);
                }
            }
        };
        task.execute();
    }

    void updateMultiTouchSupport(Context context) {
        mZoomManager.updateMultiTouchSupport(context);
    }

    void updateJavaScriptEnabled(boolean enabled) {
        if (isAccessibilityInjectionEnabled()) {
            getAccessibilityInjector().updateJavaScriptEnabled(enabled);
        }
    }

    private void init() {
        OnTrimMemoryListener.init(mContext);
        mWebView.setWillNotDraw(false);
        mWebView.setClickable(true);
        mWebView.setLongClickable(true);

        final ViewConfiguration configuration = ViewConfiguration.get(mContext);
        int slop = configuration.getScaledTouchSlop();
        mTouchSlopSquare = slop * slop;
        slop = configuration.getScaledDoubleTapSlop();
        mDoubleTapSlopSquare = slop * slop;
        final float density = WebViewCore.getFixedDisplayDensity(mContext);
        // use one line height, 16 based on our current default font, for how
        // far we allow a touch be away from the edge of a link
        mNavSlop = (int) (16 * density);
        mZoomManager.init(density);
        mMaximumFling = configuration.getScaledMaximumFlingVelocity();

        // Compute the inverse of the density squared.
        DRAG_LAYER_INVERSE_DENSITY_SQUARED = 1 / (density * density);

        mOverscrollDistance = configuration.getScaledOverscrollDistance();
        mOverflingDistance = configuration.getScaledOverflingDistance();

        setScrollBarStyle(mWebViewPrivate.super_getScrollBarStyle());
        // Initially use a size of two, since the user is likely to only hold
        // down two keys at a time (shift + another key)
        mKeysPressed = new Vector<Integer>(2);
        mHTML5VideoViewProxy = null ;
    }

    @Override
    public boolean shouldDelayChildPressedState() {
        return true;
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (!mWebView.isEnabled()) {
            // Only default actions are supported while disabled.
            return mWebViewPrivate.super_performAccessibilityAction(action, arguments);
        }

        if (getAccessibilityInjector().supportsAccessibilityAction(action)) {
            return getAccessibilityInjector().performAccessibilityAction(action, arguments);
        }

        switch (action) {
            case AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD:
            case AccessibilityNodeInfo.ACTION_SCROLL_FORWARD: {
                final int convertedContentHeight = contentToViewY(getContentHeight());
                final int adjustedViewHeight = getHeight() - mWebView.getPaddingTop()
                        - mWebView.getPaddingBottom();
                final int maxScrollY = Math.max(convertedContentHeight - adjustedViewHeight, 0);
                final boolean canScrollBackward = (getScrollY() > 0);
                final boolean canScrollForward = ((getScrollY() - maxScrollY) > 0);
                if ((action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) && canScrollBackward) {
                    mWebView.scrollBy(0, adjustedViewHeight);
                    return true;
                }
                if ((action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) && canScrollForward) {
                    mWebView.scrollBy(0, -adjustedViewHeight);
                    return true;
                }
                return false;
            }
        }

        return mWebViewPrivate.super_performAccessibilityAction(action, arguments);
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        if (!mWebView.isEnabled()) {
            // Only default actions are supported while disabled.
            return;
        }

        info.setScrollable(isScrollableForAccessibility());

        final int convertedContentHeight = contentToViewY(getContentHeight());
        final int adjustedViewHeight = getHeight() - mWebView.getPaddingTop()
                - mWebView.getPaddingBottom();
        final int maxScrollY = Math.max(convertedContentHeight - adjustedViewHeight, 0);
        final boolean canScrollBackward = (getScrollY() > 0);
        final boolean canScrollForward = ((getScrollY() - maxScrollY) > 0);

        if (canScrollForward) {
            info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
        }

        if (canScrollForward) {
            info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
        }

        getAccessibilityInjector().onInitializeAccessibilityNodeInfo(info);
    }

    @Override
    public void onInitializeAccessibilityEvent(AccessibilityEvent event) {
        event.setScrollable(isScrollableForAccessibility());
        event.setScrollX(getScrollX());
        event.setScrollY(getScrollY());
        final int convertedContentWidth = contentToViewX(getContentWidth());
        final int adjustedViewWidth = getWidth() - mWebView.getPaddingLeft()
                - mWebView.getPaddingLeft();
        event.setMaxScrollX(Math.max(convertedContentWidth - adjustedViewWidth, 0));
        final int convertedContentHeight = contentToViewY(getContentHeight());
        final int adjustedViewHeight = getHeight() - mWebView.getPaddingTop()
                - mWebView.getPaddingBottom();
        event.setMaxScrollY(Math.max(convertedContentHeight - adjustedViewHeight, 0));
    }

    /* package */ void handleSelectionChangedWebCoreThread(String selection, int token) {
        if (isAccessibilityInjectionEnabled()) {
            getAccessibilityInjector().onSelectionStringChangedWebCoreThread(selection, token);
        }
    }

    private boolean isAccessibilityInjectionEnabled() {
        final AccessibilityManager manager = AccessibilityManager.getInstance(mContext);
        if (!manager.isEnabled()) {
            return false;
        }

        // Accessibility scripts should be injected only when a speaking service
        // is enabled. This may need to change later to accommodate Braille.
        final List<AccessibilityServiceInfo> services = manager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_SPOKEN);
        if (services.isEmpty()) {
            return false;
        }

        return true;
    }

    private AccessibilityInjector getAccessibilityInjector() {
        if (mAccessibilityInjector == null) {
            mAccessibilityInjector = new AccessibilityInjector(this);
        }
        return mAccessibilityInjector;
    }

    private boolean isScrollableForAccessibility() {
        return (contentToViewX(getContentWidth()) > getWidth() - mWebView.getPaddingLeft()
                - mWebView.getPaddingRight()
                || contentToViewY(getContentHeight()) > getHeight() - mWebView.getPaddingTop()
                - mWebView.getPaddingBottom());
    }

    @Override
    public void setOverScrollMode(int mode) {
        if (mode != View.OVER_SCROLL_NEVER) {
            if (mOverScrollGlow == null) {
                mOverScrollGlow = new OverScrollGlow(this);
            }
        } else {
            mOverScrollGlow = null;
        }
    }

    /* package */ void adjustDefaultZoomDensity(int zoomDensity) {
        final float density = WebViewCore.getFixedDisplayDensity(mContext)
                * 100 / zoomDensity;
        updateDefaultZoomDensity(density);
    }

    /* package */ void updateDefaultZoomDensity(float density) {
        mNavSlop = (int) (16 * density);
        mZoomManager.updateDefaultZoomDensity(density);
    }

    /* package */ int getScaledNavSlop() {
        return viewToContentDimension(mNavSlop);
    }

    /* package */ boolean onSavePassword(String schemePlusHost, String username,
            String password, final Message resumeMsg) {
        boolean rVal = false;
        if (resumeMsg == null) {
            // null resumeMsg implies saving password silently
            mDatabase.setUsernamePassword(schemePlusHost, username, password);
        } else {
            if (mResumeMsg != null) {
                Log.w(LOGTAG, "onSavePassword should not be called while dialog is up");
                resumeMsg.sendToTarget();
                return true;
            }
            mResumeMsg = resumeMsg;
            final Message remember = mPrivateHandler.obtainMessage(
                    REMEMBER_PASSWORD);
            remember.getData().putString("host", schemePlusHost);
            remember.getData().putString("username", username);
            remember.getData().putString("password", password);
            remember.obj = resumeMsg;

            final Message neverRemember = mPrivateHandler.obtainMessage(
                    NEVER_REMEMBER_PASSWORD);
            neverRemember.getData().putString("host", schemePlusHost);
            neverRemember.getData().putString("username", username);
            neverRemember.getData().putString("password", password);
            neverRemember.obj = resumeMsg;

            mSavePasswordDialog = new AlertDialog.Builder(mContext)
                    .setTitle(com.android.internal.R.string.save_password_label)
                    .setMessage(com.android.internal.R.string.save_password_message)
                    .setPositiveButton(com.android.internal.R.string.save_password_notnow,
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            if (mResumeMsg != null) {
                                resumeMsg.sendToTarget();
                                mResumeMsg = null;
                            }
                            mSavePasswordDialog = null;
                        }
                    })
                    .setNeutralButton(com.android.internal.R.string.save_password_remember,
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            if (mResumeMsg != null) {
                                remember.sendToTarget();
                                mResumeMsg = null;
                            }
                            mSavePasswordDialog = null;
                        }
                    })
                    .setNegativeButton(com.android.internal.R.string.save_password_never,
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            if (mResumeMsg != null) {
                                neverRemember.sendToTarget();
                                mResumeMsg = null;
                            }
                            mSavePasswordDialog = null;
                        }
                    })
                    .setOnDismissListener(new DialogInterface.OnDismissListener() {
                        @Override
                        public void onDismiss(DialogInterface dialog) {
                            if (mResumeMsg != null) {
                                resumeMsg.sendToTarget();
                                mResumeMsg = null;
                            }
                            mSavePasswordDialog = null;
                        }
                    }).show();
            // Return true so that WebViewCore will pause while the dialog is
            // up.
            rVal = true;
        }
        return rVal;
    }

    @Override
    public void setScrollBarStyle(int style) {
        if (style == View.SCROLLBARS_INSIDE_INSET
                || style == View.SCROLLBARS_OUTSIDE_INSET) {
            mOverlayHorizontalScrollbar = mOverlayVerticalScrollbar = false;
        } else {
            mOverlayHorizontalScrollbar = mOverlayVerticalScrollbar = true;
        }
    }

    /**
     * See {@link WebView#setHorizontalScrollbarOverlay(boolean)}
     */
    @Override
    public void setHorizontalScrollbarOverlay(boolean overlay) {
        mOverlayHorizontalScrollbar = overlay;
    }

    /**
     * See {@link WebView#setVerticalScrollbarOverlay(boolean)
     */
    @Override
    public void setVerticalScrollbarOverlay(boolean overlay) {
        mOverlayVerticalScrollbar = overlay;
    }

    /**
     * See {@link WebView#overlayHorizontalScrollbar()}
     */
    @Override
    public boolean overlayHorizontalScrollbar() {
        return mOverlayHorizontalScrollbar;
    }

    /**
     * See {@link WebView#overlayVerticalScrollbar()}
     */
    @Override
    public boolean overlayVerticalScrollbar() {
        return mOverlayVerticalScrollbar;
    }

    /*
     * Return the width of the view where the content of WebView should render
     * to.
     * Note: this can be called from WebCoreThread.
     */
    /* package */ int getViewWidth() {
        if (!mWebView.isVerticalScrollBarEnabled() || mOverlayVerticalScrollbar) {
            return getWidth();
        } else {
            return Math.max(0, getWidth() - mWebView.getVerticalScrollbarWidth());
        }
    }

    // Interface to enable the browser to override title bar handling.
    public interface TitleBarDelegate {
        int getTitleHeight();
        public void onSetEmbeddedTitleBar(final View title);
    }

    /**
     * Returns the height (in pixels) of the embedded title bar (if any). Does not care about
     * scrolling
     */
    protected int getTitleHeight() {
        if (mWebView instanceof TitleBarDelegate) {
            return ((TitleBarDelegate) mWebView).getTitleHeight();
        }
        return 0;
    }

    /**
     * See {@link WebView#getVisibleTitleHeight()}
     */
    @Override
    @Deprecated
    public int getVisibleTitleHeight() {
        // Actually, this method returns the height of the embedded title bar if one is set via the
        // hidden setEmbeddedTitleBar method.
        return getVisibleTitleHeightImpl();
    }

    private int getVisibleTitleHeightImpl() {
        // need to restrict mScrollY due to over scroll
        return Math.max(getTitleHeight() - Math.max(0, getScrollY()),
                getOverlappingActionModeHeight());
    }

    private int mCachedOverlappingActionModeHeight = -1;

    private int getOverlappingActionModeHeight() {
        if (mFindCallback == null) {
            return 0;
        }
        if (mCachedOverlappingActionModeHeight < 0) {
            mWebView.getGlobalVisibleRect(mGlobalVisibleRect, mGlobalVisibleOffset);
            mCachedOverlappingActionModeHeight = Math.max(0,
                    mFindCallback.getActionModeGlobalBottom() - mGlobalVisibleRect.top);
        }
        return mCachedOverlappingActionModeHeight;
    }

    /*
     * Return the height of the view where the content of WebView should render
     * to.  Note that this excludes mTitleBar, if there is one.
     * Note: this can be called from WebCoreThread.
     */
    /* package */ int getViewHeight() {
        return getViewHeightWithTitle() - getVisibleTitleHeightImpl();
    }

    int getViewHeightWithTitle() {
        int height = getHeight();
        if (mWebView.isHorizontalScrollBarEnabled() && !mOverlayHorizontalScrollbar) {
            height -= mWebViewPrivate.getHorizontalScrollbarHeight();
        }
        return height;
    }

    /**
     * See {@link WebView#getCertificate()}
     */
    @Override
    public SslCertificate getCertificate() {
        return mCertificate;
    }

    /**
     * See {@link WebView#setCertificate(SslCertificate)}
     */
    @Override
    public void setCertificate(SslCertificate certificate) {
        if (DebugFlags.WEB_VIEW) {
            Log.v(LOGTAG, "setCertificate=" + certificate);
        }
        // here, the certificate can be null (if the site is not secure)
        mCertificate = certificate;
    }

    //-------------------------------------------------------------------------
    // Methods called by activity
    //-------------------------------------------------------------------------

    /**
     * See {@link WebView#savePassword(String, String, String)}
     */
    @Override
    public void savePassword(String host, String username, String password) {
        mDatabase.setUsernamePassword(host, username, password);
    }

    /**
     * See {@link WebView#setHttpAuthUsernamePassword(String, String, String, String)}
     */
    @Override
    public void setHttpAuthUsernamePassword(String host, String realm,
            String username, String password) {
        mDatabase.setHttpAuthUsernamePassword(host, realm, username, password);
    }

    /**
     * See {@link WebView#getHttpAuthUsernamePassword(String, String)}
     */
    @Override
    public String[] getHttpAuthUsernamePassword(String host, String realm) {
        return mDatabase.getHttpAuthUsernamePassword(host, realm);
    }

    /**
     * Remove Find or Select ActionModes, if active.
     */
    private void clearActionModes() {
        if (mSelectCallback != null) {
            mSelectCallback.finish();
        }
        if (mFindCallback != null) {
            mFindCallback.finish();
        }
    }

    /**
     * Called to clear state when moving from one page to another, or changing
     * in some other way that makes elements associated with the current page
     * (such as ActionModes) no longer relevant.
     */
    private void clearHelpers() {
        hideSoftKeyboard();
        clearActionModes();
        dismissFullScreenMode();
        cancelDialogs();
    }

    private void cancelDialogs() {
        if (mListBoxDialog != null) {
            mListBoxDialog.cancel();
            mListBoxDialog = null;
        }
        if (mSavePasswordDialog != null) {
            mSavePasswordDialog.dismiss();
            mSavePasswordDialog = null;
        }
    }

    /**
     * See {@link WebView#destroy()}
     */
    @Override
    public void destroy() {
        if (mWebView.getViewRootImpl() != null) {
            Log.e(LOGTAG, Log.getStackTraceString(
                    new Throwable("Error: WebView.destroy() called while still attached!")));
        }
        ensureFunctorDetached();
        destroyJava();
        destroyNative();
    }

    private void ensureFunctorDetached() {
        if (mWebView.isHardwareAccelerated()) {
            int drawGLFunction = nativeGetDrawGLFunction(mNativeClass);
            ViewRootImpl viewRoot = mWebView.getViewRootImpl();
            if (drawGLFunction != 0 && viewRoot != null) {
                viewRoot.detachFunctor(drawGLFunction);
            }
        }
    }

    private void destroyJava() {
        mCallbackProxy.blockMessages();
        if (mAccessibilityInjector != null) {
            mAccessibilityInjector.destroy();
            mAccessibilityInjector = null;
        }
        if (mWebViewCore != null) {
            // Tell WebViewCore to destroy itself
            synchronized (this) {
                WebViewCore webViewCore = mWebViewCore;
                mWebViewCore = null; // prevent using partial webViewCore
                webViewCore.destroy();
            }
            // Remove any pending messages that might not be serviced yet.
            mPrivateHandler.removeCallbacksAndMessages(null);
        }
    }

    private void destroyNative() {
        if (mNativeClass == 0) return;
        int nptr = mNativeClass;
        mNativeClass = 0;
        if (Thread.currentThread() == mPrivateHandler.getLooper().getThread()) {
            // We are on the main thread and can safely delete
            nativeDestroy(nptr);
        } else {
            mPrivateHandler.post(new DestroyNativeRunnable(nptr));
        }
    }

    private static class DestroyNativeRunnable implements Runnable {

        private int mNativePtr;

        public DestroyNativeRunnable(int nativePtr) {
            mNativePtr = nativePtr;
        }

        @Override
        public void run() {
            // nativeDestroy also does a stopGL()
            nativeDestroy(mNativePtr);
        }

    }

    /**
     * See {@link WebView#enablePlatformNotifications()}
     */
    @Deprecated
    public static void enablePlatformNotifications() {
        synchronized (WebViewClassic.class) {
            sNotificationsEnabled = true;
            Context context = JniUtil.getContext();
            if (context != null)
                setupProxyListener(context);
        }
    }

    /**
     * See {@link WebView#disablePlatformNotifications()}
     */
    @Deprecated
    public static void disablePlatformNotifications() {
        synchronized (WebViewClassic.class) {
            sNotificationsEnabled = false;
            Context context = JniUtil.getContext();
            if (context != null)
                disableProxyListener(context);
        }
    }

    /**
     * Sets JavaScript engine flags.
     *
     * @param flags JS engine flags in a String
     *
     * This is an implementation detail.
     */
    public void setJsFlags(String flags) {
        mWebViewCore.sendMessage(EventHub.SET_JS_FLAGS, flags);
    }

    /**
     * See {@link WebView#setNetworkAvailable(boolean)}
     */
    @Override
    public void setNetworkAvailable(boolean networkUp) {
        mWebViewCore.sendMessage(EventHub.SET_NETWORK_STATE,
                networkUp ? 1 : 0, 0);
    }

    /**
     * Inform WebView about the current network type.
     */
    public void setNetworkType(String type, String subtype) {
        Map<String, String> map = new HashMap<String, String>();
        map.put("type", type);
        map.put("subtype", subtype);
        mWebViewCore.sendMessage(EventHub.SET_NETWORK_TYPE, map);
    }

    /**
     * See {@link WebView#saveState(Bundle)}
     */
    @Override
    public WebBackForwardList saveState(Bundle outState) {
        if (outState == null) {
            return null;
        }
        // We grab a copy of the back/forward list because a client of WebView
        // may have invalidated the history list by calling clearHistory.
        WebBackForwardListClassic list = copyBackForwardList();
        final int currentIndex = list.getCurrentIndex();
        final int size = list.getSize();
        // We should fail saving the state if the list is empty or the index is
        // not in a valid range.
        if (currentIndex < 0 || currentIndex >= size || size == 0) {
            return null;
        }
        outState.putInt("index", currentIndex);
        // FIXME: This should just be a byte[][] instead of ArrayList but
        // Parcel.java does not have the code to handle multi-dimensional
        // arrays.
        ArrayList<byte[]> history = new ArrayList<byte[]>(size);
        for (int i = 0; i < size; i++) {
            WebHistoryItemClassic item = list.getItemAtIndex(i);
            if (null == item) {
                // FIXME: this shouldn't happen
                // need to determine how item got set to null
                Log.w(LOGTAG, "saveState: Unexpected null history item.");
                return null;
            }
            byte[] data = item.getFlattenedData();
            if (data == null) {
                // It would be very odd to not have any data for a given history
                // item. And we will fail to rebuild the history list without
                // flattened data.
                return null;
            }
            history.add(data);
        }
        outState.putSerializable("history", history);
        if (mCertificate != null) {
            outState.putBundle("certificate",
                               SslCertificate.saveState(mCertificate));
        }
        outState.putBoolean("privateBrowsingEnabled", isPrivateBrowsingEnabled());
        mZoomManager.saveZoomState(outState);
        return list;
    }

    /**
     * See {@link WebView#savePicture(Bundle, File)}
     */
    @Override
    @Deprecated
    public boolean savePicture(Bundle b, final File dest) {
        if (dest == null || b == null) {
            return false;
        }
        final Picture p = capturePicture();
        // Use a temporary file while writing to ensure the destination file
        // contains valid data.
        final File temp = new File(dest.getPath() + ".writing");
        new Thread(new Runnable() {
            @Override
            public void run() {
                FileOutputStream out = null;
                try {
                    out = new FileOutputStream(temp);
                    p.writeToStream(out);
                    // Writing the picture succeeded, rename the temporary file
                    // to the destination.
                    temp.renameTo(dest);
                } catch (Exception e) {
                    // too late to do anything about it.
                } finally {
                    if (out != null) {
                        try {
                            out.close();
                        } catch (Exception e) {
                            // Can't do anything about that
                        }
                    }
                    temp.delete();
                }
            }
        }).start();
        // now update the bundle
        b.putInt("scrollX", getScrollX());
        b.putInt("scrollY", getScrollY());
        mZoomManager.saveZoomState(b);
        return true;
    }

    private void restoreHistoryPictureFields(Picture p, Bundle b) {
        int sx = b.getInt("scrollX", 0);
        int sy = b.getInt("scrollY", 0);

        mDrawHistory = true;
        mHistoryPicture = p;

        setScrollXRaw(sx);
        setScrollYRaw(sy);
        mZoomManager.restoreZoomState(b);
        final float scale = mZoomManager.getScale();
        mHistoryWidth = Math.round(p.getWidth() * scale);
        mHistoryHeight = Math.round(p.getHeight() * scale);

        invalidate();
    }

    /**
     * See {@link WebView#restorePicture(Bundle, File)};
     */
    @Override
    @Deprecated
    public boolean restorePicture(Bundle b, File src) {
        if (src == null || b == null) {
            return false;
        }
        if (!src.exists()) {
            return false;
        }
        try {
            final FileInputStream in = new FileInputStream(src);
            final Bundle copy = new Bundle(b);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        final Picture p = Picture.createFromStream(in);
                        if (p != null) {
                            // Post a runnable on the main thread to update the
                            // history picture fields.
                            mPrivateHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    restoreHistoryPictureFields(p, copy);
                                }
                            });
                        }
                    } finally {
                        try {
                            in.close();
                        } catch (Exception e) {
                            // Nothing we can do now.
                        }
                    }
                }
            }).start();
        } catch (FileNotFoundException e){
            e.printStackTrace();
        }
        return true;
    }

    /**
     * Saves the view data to the output stream. The output is highly
     * version specific, and may not be able to be loaded by newer versions
     * of WebView.
     * @param stream The {@link OutputStream} to save to
     * @param callback The {@link ValueCallback} to call with the result
     */
    public void saveViewState(OutputStream stream, ValueCallback<Boolean> callback) {
        if (mWebViewCore == null) {
            callback.onReceiveValue(false);
            return;
        }
        mWebViewCore.sendMessageAtFrontOfQueue(EventHub.SAVE_VIEW_STATE,
                new WebViewCore.SaveViewStateRequest(stream, callback));
    }

    /**
     * Loads the view data from the input stream. See
     * {@link #saveViewState(java.io.OutputStream, ValueCallback)} for more information.
     * @param stream The {@link InputStream} to load from
     */
    public void loadViewState(InputStream stream) {
        mBlockWebkitViewMessages = true;
        new AsyncTask<InputStream, Void, DrawData>() {

            @Override
            protected DrawData doInBackground(InputStream... params) {
                try {
                    return ViewStateSerializer.deserializeViewState(params[0]);
                } catch (IOException e) {
                    return null;
                }
            }

            @Override
            protected void onPostExecute(DrawData draw) {
                if (draw == null) {
                    Log.e(LOGTAG, "Failed to load view state!");
                    return;
                }
                int viewWidth = getViewWidth();
                int viewHeight = getViewHeightWithTitle() - getTitleHeight();
                draw.mViewSize = new Point(viewWidth, viewHeight);
                draw.mViewState.mDefaultScale = getDefaultZoomScale();
                mLoadedPicture = draw;
                setNewPicture(mLoadedPicture, true);
                mLoadedPicture.mViewState = null;
            }

        }.execute(stream);
    }

    /**
     * Clears the view state set with {@link #loadViewState(InputStream)}.
     * This WebView will then switch to showing the content from webkit
     */
    public void clearViewState() {
        mBlockWebkitViewMessages = false;
        mLoadedPicture = null;
        invalidate();
    }

    /**
     * See {@link WebView#restoreState(Bundle)}
     */
    @Override
    public WebBackForwardList restoreState(Bundle inState) {
        WebBackForwardListClassic returnList = null;
        if (inState == null) {
            return returnList;
        }
        if (inState.containsKey("index") && inState.containsKey("history")) {
            mCertificate = SslCertificate.restoreState(
                inState.getBundle("certificate"));

            final WebBackForwardListClassic list = mCallbackProxy.getBackForwardList();
            final int index = inState.getInt("index");
            // We can't use a clone of the list because we need to modify the
            // shared copy, so synchronize instead to prevent concurrent
            // modifications.
            synchronized (list) {
                final List<byte[]> history =
                        (List<byte[]>) inState.getSerializable("history");
                final int size = history.size();
                // Check the index bounds so we don't crash in native code while
                // restoring the history index.
                if (index < 0 || index >= size) {
                    return null;
                }
                for (int i = 0; i < size; i++) {
                    byte[] data = history.remove(0);
                    if (data == null) {
                        // If we somehow have null data, we cannot reconstruct
                        // the item and thus our history list cannot be rebuilt.
                        return null;
                    }
                    WebHistoryItem item = new WebHistoryItemClassic(data);
                    list.addHistoryItem(item);
                }
                // Grab the most recent copy to return to the caller.
                returnList = copyBackForwardList();
                // Update the copy to have the correct index.
                returnList.setCurrentIndex(index);
            }
            // Restore private browsing setting.
            if (inState.getBoolean("privateBrowsingEnabled")) {
                getSettings().setPrivateBrowsingEnabled(true);
            }
            mZoomManager.restoreZoomState(inState);
            // Remove all pending messages because we are restoring previous
            // state.
            mWebViewCore.removeMessages();
            if (isAccessibilityInjectionEnabled()) {
                getAccessibilityInjector().addAccessibilityApisIfNecessary();
            }
            // Send a restore state message.
            mWebViewCore.sendMessage(EventHub.RESTORE_STATE, index);
        }
        return returnList;
    }

    /**
     * See {@link WebView#loadUrl(String, Map)}
     */
    @Override
    public void loadUrl(String url, Map<String, String> additionalHttpHeaders) {
        loadUrlImpl(url, additionalHttpHeaders);
    }

    private void loadUrlImpl(String url, Map<String, String> extraHeaders) {
        switchOutDrawHistory();
        WebViewCore.GetUrlData arg = new WebViewCore.GetUrlData();
        arg.mUrl = url;
        arg.mExtraHeaders = extraHeaders;
        mWebViewCore.sendMessage(EventHub.LOAD_URL, arg);
        clearHelpers();
    }

    /**
     * See {@link WebView#loadUrl(String)}
     */
    @Override
    public void loadUrl(String url) {
        loadUrlImpl(url);
    }

    private void loadUrlImpl(String url) {
        if (url == null) {
            return;
        }
        loadUrlImpl(url, null);
    }

    /**
     * See {@link WebView#postUrl(String, byte[])}
     */
    @Override
    public void postUrl(String url, byte[] postData) {
        if (URLUtil.isNetworkUrl(url)) {
            switchOutDrawHistory();
            WebViewCore.PostUrlData arg = new WebViewCore.PostUrlData();
            arg.mUrl = url;
            arg.mPostData = postData;
            mWebViewCore.sendMessage(EventHub.POST_URL, arg);
            clearHelpers();
        } else {
            loadUrlImpl(url);
        }
    }

    /**
     * See {@link WebView#loadData(String, String, String)}
     */
    @Override
    public void loadData(String data, String mimeType, String encoding) {
        loadDataImpl(data, mimeType, encoding);
    }

    private void loadDataImpl(String data, String mimeType, String encoding) {
        StringBuilder dataUrl = new StringBuilder("data:");
        dataUrl.append(mimeType);
        if ("base64".equals(encoding)) {
            dataUrl.append(";base64");
        }
        dataUrl.append(",");
        dataUrl.append(data);
        loadUrlImpl(dataUrl.toString());
    }

    /**
     * See {@link WebView#loadDataWithBaseURL(String, String, String, String, String)}
     */
    @Override
    public void loadDataWithBaseURL(String baseUrl, String data,
            String mimeType, String encoding, String historyUrl) {

        if (baseUrl != null && baseUrl.toLowerCase().startsWith("data:")) {
            loadDataImpl(data, mimeType, encoding);
            return;
        }
        switchOutDrawHistory();
        WebViewCore.BaseUrlData arg = new WebViewCore.BaseUrlData();
        arg.mBaseUrl = baseUrl;
        arg.mData = data;
        arg.mMimeType = mimeType;
        arg.mEncoding = encoding;
        arg.mHistoryUrl = historyUrl;
        mWebViewCore.sendMessage(EventHub.LOAD_DATA, arg);
        clearHelpers();
    }

    /**
     * See {@link WebView#saveWebArchive(String)}
     */
    @Override
    public void saveWebArchive(String filename) {
        saveWebArchiveImpl(filename, false, null);
    }

    /* package */ static class SaveWebArchiveMessage {
        SaveWebArchiveMessage (String basename, boolean autoname, ValueCallback<String> callback) {
            mBasename = basename;
            mAutoname = autoname;
            mCallback = callback;
        }

        /* package */ final String mBasename;
        /* package */ final boolean mAutoname;
        /* package */ final ValueCallback<String> mCallback;
        /* package */ String mResultFile;
    }

    /**
     * See {@link WebView#saveWebArchive(String, boolean, ValueCallback)}
     */
    @Override
    public void saveWebArchive(String basename, boolean autoname, ValueCallback<String> callback) {
        saveWebArchiveImpl(basename, autoname, callback);
    }

    private void saveWebArchiveImpl(String basename, boolean autoname,
            ValueCallback<String> callback) {
        mWebViewCore.sendMessage(EventHub.SAVE_WEBARCHIVE,
            new SaveWebArchiveMessage(basename, autoname, callback));
    }

    /**
     * See {@link WebView#stopLoading()}
     */
    @Override
    public void stopLoading() {
        // TODO: should we clear all the messages in the queue before sending
        // STOP_LOADING?
        switchOutDrawHistory();
        mWebViewCore.sendMessage(EventHub.STOP_LOADING);
    }

    /**
     * See {@link WebView#reload()}
     */
    @Override
    public void reload() {
        clearHelpers();
        switchOutDrawHistory();
        mWebViewCore.sendMessage(EventHub.RELOAD);
    }

    /**
     * See {@link WebView#canGoBack()}
     */
    @Override
    public boolean canGoBack() {
        WebBackForwardListClassic l = mCallbackProxy.getBackForwardList();
        synchronized (l) {
            if (l.getClearPending()) {
                return false;
            } else {
                return l.getCurrentIndex() > 0;
            }
        }
    }

    /**
     * See {@link WebView#goBack()}
     */
    @Override
    public void goBack() {
        goBackOrForwardImpl(-1);
    }

    /**
     * See {@link WebView#canGoForward()}
     */
    @Override
    public boolean canGoForward() {
        WebBackForwardListClassic l = mCallbackProxy.getBackForwardList();
        synchronized (l) {
            if (l.getClearPending()) {
                return false;
            } else {
                return l.getCurrentIndex() < l.getSize() - 1;
            }
        }
    }

    /**
     * See {@link WebView#goForward()}
     */
    @Override
    public void goForward() {
        goBackOrForwardImpl(1);
    }

    /**
     * See {@link WebView#canGoBackOrForward(int)}
     */
    @Override
    public boolean canGoBackOrForward(int steps) {
        WebBackForwardListClassic l = mCallbackProxy.getBackForwardList();
        synchronized (l) {
            if (l.getClearPending()) {
                return false;
            } else {
                int newIndex = l.getCurrentIndex() + steps;
                return newIndex >= 0 && newIndex < l.getSize();
            }
        }
    }

    /**
     * See {@link WebView#goBackOrForward(int)}
     */
    @Override
    public void goBackOrForward(int steps) {
        goBackOrForwardImpl(steps);
    }

    private void goBackOrForwardImpl(int steps) {
        goBackOrForward(steps, false);
    }

    private void goBackOrForward(int steps, boolean ignoreSnapshot) {
        if (steps != 0) {
            clearHelpers();
            mWebViewCore.sendMessage(EventHub.GO_BACK_FORWARD, steps,
                    ignoreSnapshot ? 1 : 0);
        }
    }

    /**
     * See {@link WebView#isPrivateBrowsingEnabled()}
     */
    @Override
    public boolean isPrivateBrowsingEnabled() {
        WebSettingsClassic settings = getSettings();
        return (settings != null) ? settings.isPrivateBrowsingEnabled() : false;
    }

    private void startPrivateBrowsing() {
        getSettings().setPrivateBrowsingEnabled(true);
    }

    private boolean extendScroll(int y) {
        int finalY = mScroller.getFinalY();
        int newY = pinLocY(finalY + y);
        if (newY == finalY) return false;
        mScroller.setFinalY(newY);
        mScroller.extendDuration(computeDuration(0, y));
        return true;
    }

    /**
     * See {@link WebView#pageUp(boolean)}
     */
    @Override
    public boolean pageUp(boolean top) {
        if (mNativeClass == 0) {
            return false;
        }
        if (top) {
            // go to the top of the document
            return pinScrollTo(getScrollX(), 0, true, 0);
        }
        // Page up
        int h = getHeight();
        int y;
        if (h > 2 * PAGE_SCROLL_OVERLAP) {
            y = -h + PAGE_SCROLL_OVERLAP;
        } else {
            y = -h / 2;
        }
        return mScroller.isFinished() ? pinScrollBy(0, y, true, 0)
                : extendScroll(y);
    }

    /**
     * See {@link WebView#pageDown(boolean)}
     */
    @Override
    public boolean pageDown(boolean bottom) {
        if (mNativeClass == 0) {
            return false;
        }
        if (bottom) {
            return pinScrollTo(getScrollX(), computeRealVerticalScrollRange(), true, 0);
        }
        // Page down.
        int h = getHeight();
        int y;
        if (h > 2 * PAGE_SCROLL_OVERLAP) {
            y = h - PAGE_SCROLL_OVERLAP;
        } else {
            y = h / 2;
        }
        return mScroller.isFinished() ? pinScrollBy(0, y, true, 0)
                : extendScroll(y);
    }

    /**
     * See {@link WebView#clearView()}
     */
    @Override
    public void clearView() {
        mContentWidth = 0;
        mContentHeight = 0;
        setBaseLayer(0, false, false);
        mWebViewCore.sendMessage(EventHub.CLEAR_CONTENT);
    }

    /**
     * See {@link WebView#capturePicture()}
     */
    @Override
    public Picture capturePicture() {
        if (mNativeClass == 0) return null;
        Picture result = new Picture();
        nativeCopyBaseContentToPicture(result);
        return result;
    }

    /**
     * See {@link WebView#getScale()}
     */
    @Override
    public float getScale() {
        return mZoomManager.getScale();
    }

    /**
     * Compute the reading level scale of the WebView
     * @param scale The current scale.
     * @return The reading level scale.
     */
    /*package*/ float computeReadingLevelScale(float scale) {
        return mZoomManager.computeReadingLevelScale(scale);
    }

    /**
     * See {@link WebView#setInitialScale(int)}
     */
    @Override
    public void setInitialScale(int scaleInPercent) {
        mZoomManager.setInitialScaleInPercent(scaleInPercent);
    }

    /**
     * See {@link WebView#invokeZoomPicker()}
     */
    @Override
    public void invokeZoomPicker() {
        if (!getSettings().supportZoom()) {
            Log.w(LOGTAG, "This WebView doesn't support zoom.");
            return;
        }
        clearHelpers();
        mZoomManager.invokeZoomPicker();
    }

    /**
     * See {@link WebView#getHitTestResult()}
     */
    @Override
    public HitTestResult getHitTestResult() {
        return mInitialHitTestResult;
    }

    // No left edge for double-tap zoom alignment
    static final int NO_LEFTEDGE = -1;

    int getBlockLeftEdge(int x, int y, float readingScale) {
        float invReadingScale = 1.0f / readingScale;
        int readingWidth = (int) (getViewWidth() * invReadingScale);
        int left = NO_LEFTEDGE;
        if (mFocusedNode != null) {
            final int length = mFocusedNode.mEnclosingParentRects.length;
            for (int i = 0; i < length; i++) {
                Rect rect = mFocusedNode.mEnclosingParentRects[i];
                if (rect.width() < mFocusedNode.mHitTestSlop) {
                    // ignore bounding boxes that are too small
                    continue;
                } else if (rect.width() > readingWidth) {
                    // stop when bounding box doesn't fit the screen width
                    // at reading scale
                    break;
                }

                left = rect.left;
            }
        }

        return left;
    }

    /**
     * See {@link WebView#requestFocusNodeHref(Message)}
     */
    @Override
    public void requestFocusNodeHref(Message hrefMsg) {
        if (hrefMsg == null) {
            return;
        }
        int contentX = viewToContentX(mLastTouchX + getScrollX());
        int contentY = viewToContentY(mLastTouchY + getScrollY());
        if (mFocusedNode != null && mFocusedNode.mHitTestX == contentX
                && mFocusedNode.mHitTestY == contentY) {
            hrefMsg.getData().putString(FocusNodeHref.URL, mFocusedNode.mLinkUrl);
            hrefMsg.getData().putString(FocusNodeHref.TITLE, mFocusedNode.mAnchorText);
            hrefMsg.getData().putString(FocusNodeHref.SRC, mFocusedNode.mImageUrl);
            hrefMsg.sendToTarget();
            return;
        }
        mWebViewCore.sendMessage(EventHub.REQUEST_CURSOR_HREF,
                contentX, contentY, hrefMsg);
    }

    /**
     * See {@link WebView#requestImageRef(Message)}
     */
    @Override
    public void requestImageRef(Message msg) {
        if (0 == mNativeClass) return; // client isn't initialized
        String url = mFocusedNode != null ? mFocusedNode.mImageUrl : null;
        Bundle data = msg.getData();
        data.putString("url", url);
        msg.setData(data);
        msg.sendToTarget();
    }

    static int pinLoc(int x, int viewMax, int docMax) {
//        Log.d(LOGTAG, "-- pinLoc " + x + " " + viewMax + " " + docMax);
        if (docMax < viewMax) {   // the doc has room on the sides for "blank"
            // pin the short document to the top/left of the screen
            x = 0;
//            Log.d(LOGTAG, "--- center " + x);
        } else if (x < 0) {
            x = 0;
//            Log.d(LOGTAG, "--- zero");
        } else if (x + viewMax > docMax) {
            x = docMax - viewMax;
//            Log.d(LOGTAG, "--- pin " + x);
        }
        return x;
    }

    // Expects x in view coordinates
    int pinLocX(int x) {
        if (mInOverScrollMode) return x;
        return pinLoc(x, getViewWidth(), computeRealHorizontalScrollRange());
    }

    // Expects y in view coordinates
    int pinLocY(int y) {
        if (mInOverScrollMode) return y;
        return pinLoc(y, getViewHeightWithTitle(),
                      computeRealVerticalScrollRange() + getTitleHeight());
    }

    /**
     * Given a distance in view space, convert it to content space. Note: this
     * does not reflect translation, just scaling, so this should not be called
     * with coordinates, but should be called for dimensions like width or
     * height.
     */
    private int viewToContentDimension(int d) {
        return Math.round(d * mZoomManager.getInvScale());
    }

    /**
     * Given an x coordinate in view space, convert it to content space.  Also
     * may be used for absolute heights.
     */
    /*package*/ int viewToContentX(int x) {
        return viewToContentDimension(x);
    }

    /**
     * Given a y coordinate in view space, convert it to content space.
     * Takes into account the height of the title bar if there is one
     * embedded into the WebView.
     */
    /*package*/ int viewToContentY(int y) {
        return viewToContentDimension(y - getTitleHeight());
    }

    /**
     * Given a x coordinate in view space, convert it to content space.
     * Returns the result as a float.
     */
    private float viewToContentXf(int x) {
        return x * mZoomManager.getInvScale();
    }

    /**
     * Given a y coordinate in view space, convert it to content space.
     * Takes into account the height of the title bar if there is one
     * embedded into the WebView. Returns the result as a float.
     */
    private float viewToContentYf(int y) {
        return (y - getTitleHeight()) * mZoomManager.getInvScale();
    }

    /**
     * Given a distance in content space, convert it to view space. Note: this
     * does not reflect translation, just scaling, so this should not be called
     * with coordinates, but should be called for dimensions like width or
     * height.
     */
    /*package*/ int contentToViewDimension(int d) {
        return Math.round(d * mZoomManager.getScale());
    }

    /**
     * Given an x coordinate in content space, convert it to view
     * space.
     */
    /*package*/ int contentToViewX(int x) {
        return contentToViewDimension(x);
    }

    /**
     * Given a y coordinate in content space, convert it to view
     * space.  Takes into account the height of the title bar.
     */
    /*package*/ int contentToViewY(int y) {
        return contentToViewDimension(y) + getTitleHeight();
    }

    private Rect contentToViewRect(Rect x) {
        return new Rect(contentToViewX(x.left), contentToViewY(x.top),
                        contentToViewX(x.right), contentToViewY(x.bottom));
    }

    /*  To invalidate a rectangle in content coordinates, we need to transform
        the rect into view coordinates, so we can then call invalidate(...).

        Normally, we would just call contentToView[XY](...), which eventually
        calls Math.round(coordinate * mActualScale). However, for invalidates,
        we need to account for the slop that occurs with antialiasing. To
        address that, we are a little more liberal in the size of the rect that
        we invalidate.

        This liberal calculation calls floor() for the top/left, and ceil() for
        the bottom/right coordinates. This catches the possible extra pixels of
        antialiasing that we might have missed with just round().
     */

    // Called by JNI to invalidate the View, given rectangle coordinates in
    // content space
    private void viewInvalidate(int l, int t, int r, int b) {
        final float scale = mZoomManager.getScale();
        final int dy = getTitleHeight();
        mWebView.invalidate((int)Math.floor(l * scale),
                (int)Math.floor(t * scale) + dy,
                (int)Math.ceil(r * scale),
                (int)Math.ceil(b * scale) + dy);
    }

    // Called by JNI to invalidate the View after a delay, given rectangle
    // coordinates in content space
    private void viewInvalidateDelayed(long delay, int l, int t, int r, int b) {
        final float scale = mZoomManager.getScale();
        final int dy = getTitleHeight();
        mWebView.postInvalidateDelayed(delay,
                (int)Math.floor(l * scale),
                (int)Math.floor(t * scale) + dy,
                (int)Math.ceil(r * scale),
                (int)Math.ceil(b * scale) + dy);
    }

    private void invalidateContentRect(Rect r) {
        viewInvalidate(r.left, r