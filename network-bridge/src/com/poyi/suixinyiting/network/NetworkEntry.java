package com.poyi.suixinyiting.network;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.RecyclerView;

/** Inserts network sources into the mother menu's actual RecyclerView data flow. */
public final class NetworkEntry {
    private NetworkEntry() {}

    public static void install(final Activity activity) {
        final View content = activity.findViewById(android.R.id.content);
        if (content == null || "suixin_network_entries".equals(content.getTag())) return;
        final RecyclerView recycler = findRecycler(content);
        if (recycler == null) return;
        content.setTag("suixin_network_pending");
        recycler.postDelayed(new Runnable() {
            @Override public void run() {
                RecyclerView.Adapter<?> original = recycler.getAdapter();
                if (original == null) {
                    content.setTag(null);
                    install(activity);
                    return;
                }
                if (!(original instanceof ConcatAdapter))
                    recycler.setAdapter(new ConcatAdapter(new SourceAdapter(activity), original));
                wireQueue(activity, recycler);
                content.setTag("suixin_network_entries");
            }
        }, 500);
    }

    private static void wireQueue(final Activity activity, final RecyclerView recycler) {
        RecyclerView.OnChildAttachStateChangeListener listener =
                new RecyclerView.OnChildAttachStateChangeListener() {
            @Override public void onChildViewAttachedToWindow(View view) {
                TextView title = findText(view, "播放队列");
                if (title == null) return;
                view.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View ignored) {
                        activity.startActivity(new Intent(activity, NetworkMusicActivity.class)
                                .putExtra("mode", "queue"));
                    }
                });
            }
            @Override public void onChildViewDetachedFromWindow(View view) {}
        };
        recycler.addOnChildAttachStateChangeListener(listener);
        for (int i = 0; i < recycler.getChildCount(); i++)
            listener.onChildViewAttachedToWindow(recycler.getChildAt(i));
    }

    private static TextView findText(View root, String wanted) {
        if (root instanceof TextView && wanted.contentEquals(((TextView) root).getText()))
            return (TextView) root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView result = findText(group.getChildAt(i), wanted);
                if (result != null) return result;
            }
        }
        return null;
    }

    private static final class SourceAdapter extends RecyclerView.Adapter<SourceHolder> {
        private final Activity activity;
        SourceAdapter(Activity activity) { this.activity = activity; }

        @Override public int getItemCount() { return 3; }

        @Override public SourceHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(10), dp(10), dp(10));
            row.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            int background = id("bg_item", "drawable");
            if (background != 0) row.setBackgroundResource(background);

            ImageView icon = new ImageView(activity);
            icon.setAdjustViewBounds(true);
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(45), dp(45));
            iconLp.rightMargin = dp(7);
            row.addView(icon, iconLp);

            TextView title = new TextView(activity);
            title.setTextColor(Color.WHITE);
            title.setTextSize(19);
            title.setSingleLine(true);
            row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
            return new SourceHolder(row, icon, title);
        }

        @Override public void onBindViewHolder(SourceHolder holder, int position) {
            final String mode = position == 0 ? "liked"
                    : position == 1 ? "playlists" : "queue";
            holder.title.setText(position == 0 ? "我喜欢的音乐"
                    : position == 1 ? "歌单" : "播放队列");
            int icon = id(position == 0 ? "ic_default_music_store"
                    : position == 1 ? "ic_default_play_list" : "ic_default_play_queue",
                    "drawable");
            if (icon != 0) holder.icon.setImageResource(icon);
            holder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    activity.startActivity(new Intent(activity, NetworkMusicActivity.class)
                            .putExtra("mode", mode));
                }
            });
        }

        private int id(String name, String type) {
            return activity.getResources().getIdentifier(name, type, activity.getPackageName());
        }

        private int dp(int value) {
            return Math.round(value * activity.getResources().getDisplayMetrics().density);
        }
    }

    private static final class SourceHolder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView title;
        SourceHolder(View itemView, ImageView icon, TextView title) {
            super(itemView);
            this.icon = icon;
            this.title = title;
        }
    }

    private static RecyclerView findRecycler(View root) {
        if (root instanceof RecyclerView) return (RecyclerView) root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                RecyclerView result = findRecycler(group.getChildAt(i));
                if (result != null) return result;
            }
        }
        return null;
    }
}
