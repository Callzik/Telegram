package org.telegram.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.util.LruCache;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.io.File;
import java.util.HashSet;

/** Fork: built-in PDF viewer on top of android.graphics.pdf.PdfRenderer. */
public class ForkPdfViewerActivity extends BaseFragment {

    private static final int MENU_OPEN_EXTERNAL = 1;

    private final File file;
    private final String fileName;
    private final String mimeType;

    private ParcelFileDescriptor descriptor;
    private PdfRenderer renderer;
    private int pageCount;
    private float[] pageRatios;
    private int renderWidth;

    private final DispatchQueue renderQueue = new DispatchQueue("forkPdfRender");
    private final HashSet<Integer> rendering = new HashSet<>();
    private LruCache<Integer, Bitmap> cache;

    private RecyclerView listView;
    private LinearLayoutManager layoutManager;
    private ZoomLayout zoomLayout;
    private TextView pageIndicator;

    public ForkPdfViewerActivity(File file, String fileName, String mimeType) {
        this.file = file;
        this.fileName = fileName;
        this.mimeType = mimeType;
    }

    public static boolean isPdf(String fileName, String mimeType) {
        return "application/pdf".equalsIgnoreCase(mimeType) || fileName != null && fileName.toLowerCase().endsWith(".pdf");
    }

    @Override
    public boolean onFragmentCreate() {
        try {
            descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            renderer = new PdfRenderer(descriptor);
            pageCount = renderer.getPageCount();
            pageRatios = new float[pageCount];
            for (int i = 0; i < pageCount; i++) {
                PdfRenderer.Page page = renderer.openPage(i);
                pageRatios[i] = page.getWidth() > 0 ? (float) page.getHeight() / page.getWidth() : 1.414f;
                page.close();
            }
        } catch (Throwable e) {
            FileLog.e(e);
            closeRenderer();
            return false;
        }
        int maxKb = (int) Math.min(Runtime.getRuntime().maxMemory() / 1024 / 5, 96 * 1024);
        cache = new LruCache<Integer, Bitmap>(maxKb) {
            @Override
            protected int sizeOf(Integer key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        renderQueue.postRunnable(() -> {
            closeRenderer();
            renderQueue.recycle();
        });
        if (cache != null) {
            cache.evictAll();
        }
    }

    private void closeRenderer() {
        try {
            if (renderer != null) renderer.close();
        } catch (Throwable ignore) {
        }
        try {
            if (descriptor != null) descriptor.close();
        } catch (Throwable ignore) {
        }
        renderer = null;
        descriptor = null;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(fileName != null ? fileName : file.getName());
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_OPEN_EXTERNAL) {
                    if (getParentActivity() != null) {
                        AndroidUtilities.openForViewExternal(file, fileName, mimeType, getParentActivity());
                    }
                }
            }
        });
        ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(MENU_OPEN_EXTERNAL, R.drawable.msg_openin).setContentDescription(LocaleController.getString(R.string.ForkPdfOpenExternal));

        FrameLayout frame = new FrameLayout(context);
        frame.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new RecyclerView(context);
        layoutManager = new LinearLayoutManager(context);
        listView.setLayoutManager(layoutManager);
        listView.setItemAnimator(null);
        listView.setPadding(0, AndroidUtilities.dp(6), 0, AndroidUtilities.dp(6));
        listView.setClipToPadding(false);
        listView.setAdapter(new Adapter());
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                updatePageIndicator();
            }
        });

        zoomLayout = new ZoomLayout(context);
        zoomLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        frame.addView(zoomLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        pageIndicator = new TextView(context);
        pageIndicator.setTextColor(Color.WHITE);
        pageIndicator.setTextSize(13);
        pageIndicator.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(4), AndroidUtilities.dp(10), AndroidUtilities.dp(4));
        pageIndicator.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(12), 0x99000000));
        frame.addView(pageIndicator, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL, 0, 0, 0, 16));
        updatePageIndicator();

        fragmentView = frame;
        return fragmentView;
    }

    private void updatePageIndicator() {
        if (pageIndicator == null || layoutManager == null) return;
        int first = layoutManager.findFirstVisibleItemPosition();
        int last = layoutManager.findLastVisibleItemPosition();
        int page = first < 0 ? 1 : (last == pageCount - 1 && !listView.canScrollVertically(1) ? last : first) + 1;
        pageIndicator.setText(LocaleController.formatString(R.string.ForkPdfPage, page, pageCount));
    }

    private int getRenderWidth() {
        if (renderWidth == 0) {
            int w = listView != null && listView.getWidth() > 0 ? listView.getWidth() : AndroidUtilities.displaySize.x;
            renderWidth = Math.min((int) (w * 1.6f), 2048);
        }
        return renderWidth;
    }

    private void requestPage(int index) {
        if (rendering.contains(index) || renderer == null) return;
        rendering.add(index);
        final int width = getRenderWidth();
        renderQueue.postRunnable(() -> {
            Bitmap bitmap = null;
            try {
                if (renderer != null) {
                    PdfRenderer.Page page = renderer.openPage(index);
                    int height = Math.max(1, (int) (width * (float) page.getHeight() / page.getWidth()));
                    bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                    new Canvas(bitmap).drawColor(Color.WHITE);
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    page.close();
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            final Bitmap result = bitmap;
            AndroidUtilities.runOnUIThread(() -> {
                rendering.remove(index);
                if (result == null || cache == null) return;
                cache.put(index, result);
                if (listView != null) {
                    RecyclerView.ViewHolder holder = listView.findViewHolderForAdapterPosition(index);
                    if (holder != null) {
                        ((ImageView) holder.itemView).setImageBitmap(result);
                    }
                }
            });
        });
    }

    private class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ImageView view = new ImageView(parent.getContext()) {
                @Override
                protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                    int w = MeasureSpec.getSize(widthMeasureSpec) - AndroidUtilities.dp(12);
                    Object tag = getTag();
                    float ratio = tag instanceof Float ? (Float) tag : 1.414f;
                    setMeasuredDimension(w + AndroidUtilities.dp(12), (int) (w * ratio) + AndroidUtilities.dp(6));
                }
            };
            view.setScaleType(ImageView.ScaleType.FIT_CENTER);
            view.setPadding(AndroidUtilities.dp(6), AndroidUtilities.dp(3), AndroidUtilities.dp(6), AndroidUtilities.dp(3));
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerView.ViewHolder(view) {
            };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            ImageView view = (ImageView) holder.itemView;
            view.setTag(pageRatios[position]);
            Bitmap bitmap = cache.get(position);
            if (bitmap != null) {
                view.setImageBitmap(bitmap);
            } else {
                view.setImageDrawable(new android.graphics.drawable.ColorDrawable(Color.WHITE));
                requestPage(position);
            }
            view.requestLayout();
        }

        @Override
        public int getItemCount() {
            return pageCount;
        }
    }

    /** Pinch to zoom (1x..4x) and double tap, horizontal panning while zoomed; vertical scroll stays in the list. */
    private static class ZoomLayout extends FrameLayout {
        private float scale = 1f;
        private float lastX;
        private boolean scaling;
        private boolean swallow;
        private final ScaleGestureDetector scaleDetector;
        private final GestureDetector tapDetector;

        ZoomLayout(Context context) {
            super(context);
            scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScaleBegin(ScaleGestureDetector detector) {
                    scaling = true;
                    return true;
                }

                @Override
                public boolean onScale(ScaleGestureDetector detector) {
                    setScale(scale * detector.getScaleFactor(), detector.getFocusX());
                    return true;
                }

                @Override
                public void onScaleEnd(ScaleGestureDetector detector) {
                    scaling = false;
                }
            });
            tapDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    setScale(scale > 1.01f ? 1f : 2.5f, e.getX());
                    return true;
                }
            });
        }

        private void setScale(float newScale, float focusX) {
            View child = getChildAt(0);
            if (child == null) return;
            newScale = Math.max(1f, Math.min(4f, newScale));
            float w = getWidth();
            // keep the point under focusX in place
            float contentX = (focusX - child.getTranslationX() - w / 2f) / scale;
            scale = newScale;
            child.setPivotX(w / 2f);
            child.setPivotY(getHeight() / 2f);
            child.setScaleX(scale);
            child.setScaleY(scale);
            setTranslation(child, focusX - w / 2f - contentX * scale);
        }

        private void setTranslation(View child, float tx) {
            float max = (getWidth() * scale - getWidth()) / 2f;
            child.setTranslationX(Math.max(-max, Math.min(max, tx)));
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent ev) {
            scaleDetector.onTouchEvent(ev);
            tapDetector.onTouchEvent(ev);
            View child = getChildAt(0);
            if (child != null && ev.getPointerCount() == 1 && !scaling) {
                if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    lastX = ev.getX();
                } else if (ev.getActionMasked() == MotionEvent.ACTION_MOVE && scale > 1.01f && !swallow) {
                    setTranslation(child, child.getTranslationX() + ev.getX() - lastX);
                    lastX = ev.getX();
                }
            }
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                swallow = false;
            }
            if (!swallow && (ev.getPointerCount() > 1 || scaling)) {
                // don't let the list scroll while pinching; swallow the rest of this gesture
                swallow = true;
                MotionEvent cancel = MotionEvent.obtain(ev);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                super.dispatchTouchEvent(cancel);
                cancel.recycle();
            }
            if (swallow) {
                return true;
            }
            return super.dispatchTouchEvent(ev);
        }
    }
}
