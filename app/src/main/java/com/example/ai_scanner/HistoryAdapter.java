package com.example.ai_scanner;

import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.ViewHolder> {

    private final List<HistoryItem> items = new ArrayList<>();

    public void setItems(List<HistoryItem> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    public void appendItems(List<HistoryItem> newItems) {
        int startPos = items.size();
        items.addAll(newItems);
        notifyItemRangeInserted(startPos, newItems.size());
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_history_record, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(items.get(position));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        private final View riskIndicator;
        private final TextView tvAuthorId;
        private final TextView tvTime;
        private final TextView tvRiskLevel;
        private final TextView tvScore;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            riskIndicator = itemView.findViewById(R.id.vRiskIndicator);
            tvAuthorId = itemView.findViewById(R.id.tvAuthorId);
            tvTime = itemView.findViewById(R.id.tvTime);
            tvRiskLevel = itemView.findViewById(R.id.tvRiskLevel);
            tvScore = itemView.findViewById(R.id.tvScore);
        }

        void bind(HistoryItem item) {
            tvAuthorId.setText(item.authorId);
            tvTime.setText(formatTime(item.createdAt));

            int colorRes;
            String levelText;
            switch (item.riskLevel) {
                case "HIGH":
                    colorRes = R.color.risk_high;
                    levelText = itemView.getContext().getString(R.string.risk_high_label);
                    break;
                case "MEDIUM":
                    colorRes = R.color.risk_mid;
                    levelText = itemView.getContext().getString(R.string.risk_medium_label);
                    break;
                default:
                    colorRes = R.color.safe_green;
                    levelText = itemView.getContext().getString(R.string.risk_safe_label);
                    break;
            }

            int color = ContextCompat.getColor(itemView.getContext(), colorRes);
            riskIndicator.setBackgroundTintList(ColorStateList.valueOf(color));
            tvRiskLevel.setText(levelText);
            tvRiskLevel.setTextColor(color);

            tvScore.setText(itemView.getContext().getString(
                    R.string.score_format, item.score * 100));
        }

        private String formatTime(String isoTime) {
            if (isoTime == null || isoTime.length() < 16) {
                return isoTime != null ? isoTime : "";
            }
            return isoTime.substring(0, 10) + " " + isoTime.substring(11, 16);
        }
    }
}
