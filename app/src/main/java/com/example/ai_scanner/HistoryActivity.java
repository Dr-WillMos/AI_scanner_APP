package com.example.ai_scanner;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.ai_scanner.HistoryRepository.HistoryPage;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HistoryActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "history_sync_prefs";
    private static final String KEY_LAST_SYNCED_ID = "last_synced_id";
    private static final int PAGE_SIZE = 20;

    private RecyclerView rvHistory;
    private ProgressBar pbLoading;
    private ProgressBar pbLoadMore;
    private View llEmptyState;
    private View llErrorState;

    private HistoryAdapter adapter;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private String deviceId;
    private int currentPage = 1;
    private boolean hasMore = true;
    private boolean isLoading = false;
    private boolean isError = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        deviceId = DeviceIdProvider.getDeviceId(this);

        rvHistory = findViewById(R.id.rvHistory);
        pbLoading = findViewById(R.id.pbLoading);
        pbLoadMore = findViewById(R.id.pbLoadMore);
        llEmptyState = findViewById(R.id.llEmptyState);
        llErrorState = findViewById(R.id.llErrorState);
        View btnRetry = findViewById(R.id.btnRetry);
        View btnBack = findViewById(R.id.btnBack);

        adapter = new HistoryAdapter();
        rvHistory.setLayoutManager(new LinearLayoutManager(this));
        rvHistory.setAdapter(adapter);

        rvHistory.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (dy > 0 && !isLoading && hasMore && !isError) {
                    LinearLayoutManager lm = (LinearLayoutManager) recyclerView.getLayoutManager();
                    if (lm != null && lm.findLastVisibleItemPosition() >= adapter.getItemCount() - 3) {
                        loadNextPage();
                    }
                }
            }
        });

        btnRetry.setOnClickListener(v -> {
            isError = false;
            showLoading();
            loadPage(currentPage, true);
        });

        btnBack.setOnClickListener(v -> finish());

        loadPage(1, true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private void loadPage(int page, boolean replace) {
        if (isLoading) return;
        isLoading = true;

        if (replace) {
            showLoading();
        } else {
            pbLoadMore.setVisibility(View.VISIBLE);
        }

        executor.execute(() -> {
            HistoryPage result = HistoryRepository.fetchLocalHistory(
                    HistoryActivity.this, page, PAGE_SIZE);
            mainHandler.post(() -> {
                isLoading = false;
                pbLoadMore.setVisibility(View.GONE);

                if (result == null) {
                    if (replace) {
                        showError();
                    } else {
                        Toast.makeText(this, R.string.history_load_failed, Toast.LENGTH_SHORT).show();
                    }
                    return;
                }

                isError = false;
                hasMore = result.hasMore;
                currentPage = result.page;

                if (replace) {
                    adapter.setItems(result.records);
                } else {
                    adapter.appendItems(result.records);
                }

                persistLatestId(result.latestId);

                if (adapter.getItemCount() == 0) {
                    showEmpty();
                } else {
                    showContent();
                }
            });
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        executor.execute(() -> {
            HistoryRepository.syncRemoteToLocal(this, deviceId);
        });
    }

    private void loadNextPage() {
        loadPage(currentPage + 1, false);
    }

    private void persistLatestId(long latestId) {
        if (latestId > 0) {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            long stored = prefs.getLong(KEY_LAST_SYNCED_ID, 0);
            if (latestId > stored) {
                prefs.edit().putLong(KEY_LAST_SYNCED_ID, latestId).apply();
            }
        }
    }

    private void showLoading() {
        pbLoading.setVisibility(View.VISIBLE);
        rvHistory.setVisibility(View.GONE);
        llEmptyState.setVisibility(View.GONE);
        llErrorState.setVisibility(View.GONE);
    }

    private void showContent() {
        pbLoading.setVisibility(View.GONE);
        rvHistory.setVisibility(View.VISIBLE);
        llEmptyState.setVisibility(View.GONE);
        llErrorState.setVisibility(View.GONE);
    }

    private void showEmpty() {
        pbLoading.setVisibility(View.GONE);
        rvHistory.setVisibility(View.GONE);
        llEmptyState.setVisibility(View.VISIBLE);
        llErrorState.setVisibility(View.GONE);
    }

    private void showError() {
        pbLoading.setVisibility(View.GONE);
        rvHistory.setVisibility(View.GONE);
        llEmptyState.setVisibility(View.GONE);
        llErrorState.setVisibility(View.VISIBLE);
        isError = true;
    }
}
