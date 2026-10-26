package dev.cabinwright.store;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.view.View;
import android.widget.ImageView;

import dev.cabinwright.store.common.IndexCheck;
import dev.cabinwright.store.common.Index;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** App icons from the index: fetched once, checked against the index's SHA-256, cached by hash. */
final class Icons {
    private static final int MAX = 64 << 10;
    private final Store store;
    private final Handler ui = new Handler();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<String, Bitmap> memory = new HashMap<String, Bitmap>();

    Icons(Store store) {
        this.store = store;
    }

    /** Shows the app's icon in {@code view} once it is fetched and checked, hiding the placeholder. */
    void show(final Index.App app, final ImageView view, final View placeholder) {
        if (app.iconUrl.isEmpty() || app.iconSha256.length() != 64) return;
        final String sha = app.iconSha256;
        view.setTag(sha);
        Bitmap b = memory.get(sha);
        if (b != null) {
            view.setImageBitmap(b);
            placeholder.setVisibility(View.INVISIBLE);
            return;
        }
        worker.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap bmp = load(app.iconUrl, sha);
                if (bmp == null) return;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        memory.put(sha, bmp);
                        if (!sha.equals(view.getTag())) return;
                        view.setImageBitmap(bmp);
                        placeholder.setVisibility(View.INVISIBLE);
                    }
                });
            }
        });
    }

    private Bitmap load(String url, String sha) {
        File dir = new File(store.context.getCacheDir(), "icons");
        dir.mkdirs();
        File f = new File(dir, sha + ".png");
        try {
            byte[] b = f.isFile() ? Store.read(f, MAX) : null;
            if (b == null || !IndexCheck.sha256(b).equals(sha)) {
                b = new Net(store.context, store.debug()).get(url, MAX);
                if (!IndexCheck.sha256(b).equals(sha)) return null;
                Store.write(f, b);
            }
            return BitmapFactory.decodeByteArray(b, 0, b.length);
        } catch (Exception e) {
            return null;
        }
    }
}
