package fitcamx.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Opaque RDMA0 View for rendering the Gen5W OEM backup camera feed.
 *
 * Unlike SurfaceView / GLSurfaceView (which punch a transparent hole through RDMA0 down to RDMA1),
 * OemRearView draws directly onto the Activity's opaque hardware-accelerated RDMA0 UI plane with a
 * solid 0xFF000000 black background. This occludes the underlying RDMA1 hardware camera plane and
 * ensures Tap-to-Screenshot captures the live backup camera image cleanly.
 */
public class OemRearView extends View {
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint statusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix drawMatrix = new Matrix();
    private final RectF dstRect = new RectF();

    private Bitmap currentBitmap;
    private boolean mirrorMode = false;
    private String statusText = null;
    private int statusColor = 0xFFB0BEC5;

    public OemRearView(Context context) {
        super(context);
        init(context);
    }

    public OemRearView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public OemRearView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context ctx) {
        setWillNotDraw(false);
        float density = (ctx != null && ctx.getResources() != null && ctx.getResources().getDisplayMetrics() != null)
                ? ctx.getResources().getDisplayMetrics().density : 1.5f;
        statusPaint.setTextSize(14f * density);
        statusPaint.setTextAlign(Paint.Align.CENTER);
        statusPaint.setColor(statusColor);
    }

    /**
     * The raw Gen5W backup camera sensor outputs a mirror image (left side of car on left side of
     * frame). Therefore:
     * - mirrorMode == false ("Mirror: OFF", matching Fitcamx Rear default): flip horizontally so
     *   license plates and text read left-to-right.
     * - mirrorMode == true ("Mirror: ON"): leave raw sensor orientation unflipped so it acts like
     *   a rearview mirror.
     */
    public static boolean shouldFlipRawSensorHorizontally(boolean mirrorMode) {
        return !mirrorMode;
    }

    /**
     * Computes aspect-ratio-preserving fit-center bounds { left, top, right, bottom } inside
     * [0..viewW, 0..viewH].
     */
    public static float[] computeFitBounds(int viewW, int viewH, int srcW, int srcH) {
        if (viewW <= 0 || viewH <= 0 || srcW <= 0 || srcH <= 0) {
            return new float[] { 0f, 0f, 0f, 0f };
        }
        float scale = Math.min((float) viewW / (float) srcW, (float) viewH / (float) srcH);
        float drawnW = srcW * scale;
        float drawnH = srcH * scale;
        float left = (viewW - drawnW) * 0.5f;
        float top = (viewH - drawnH) * 0.5f;
        return new float[] { left, top, left + drawnW, top + drawnH };
    }

    public void setMirrorMode(boolean mirrorMode) {
        if (this.mirrorMode != mirrorMode) {
            this.mirrorMode = mirrorMode;
            invalidate();
        }
    }

    public boolean isMirrorMode() {
        return mirrorMode;
    }

    public void setStatusMessage(String message, int textColor) {
        this.statusText = message;
        this.statusColor = textColor;
        this.statusPaint.setColor(textColor);
        invalidate();
    }

    public void setFrame(Bitmap bitmap) {
        this.currentBitmap = bitmap;
        if (bitmap != null) {
            this.statusText = null;
        }
        invalidate();
    }

    public void clearFrame(String placeholderMessage, int textColor) {
        this.currentBitmap = null;
        this.statusText = placeholderMessage;
        this.statusColor = textColor;
        this.statusPaint.setColor(textColor);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // Always fill 100% opaque black on RDMA0 so any underlying RDMA1 plane is completely occluded
        canvas.drawColor(0xFF000000);

        int vw = getWidth();
        int vh = getHeight();
        if (vw <= 0 || vh <= 0) return;

        Bitmap bmp = currentBitmap;
        if (bmp != null && !bmp.isRecycled() && bmp.getWidth() > 0 && bmp.getHeight() > 0) {
            int bw = bmp.getWidth();
            int bh = bmp.getHeight();
            float[] bounds = computeFitBounds(vw, vh, bw, bh);
            dstRect.set(bounds[0], bounds[1], bounds[2], bounds[3]);

            drawMatrix.reset();
            float sx = dstRect.width() / (float) bw;
            float sy = dstRect.height() / (float) bh;
            drawMatrix.postScale(sx, sy);
            drawMatrix.postTranslate(dstRect.left, dstRect.top);
            if (shouldFlipRawSensorHorizontally(mirrorMode)) {
                drawMatrix.postScale(-1f, 1f, dstRect.centerX(), dstRect.centerY());
            }
            canvas.drawBitmap(bmp, drawMatrix, bitmapPaint);
        }

        if (statusText != null && !statusText.isEmpty()) {
            // Draw translucent pill backdrop if overlaid on top of a frozen frame
            if (bmp != null && !bmp.isRecycled()) {
                canvas.drawColor(0xAA000000);
            }
            float cx = vw * 0.5f;
            float cy = vh * 0.5f - ((statusPaint.descent() + statusPaint.ascent()) * 0.5f);
            canvas.drawText(statusText, cx, cy, statusPaint);
        }
    }
}
