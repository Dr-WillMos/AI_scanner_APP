"""
轻量 Mock 后端 + AI 服务 — 一站式替代 Spring Boot 后端和 Python AI 服务。

启动:
  pip install flask
  python mock_ai_server.py

默认监听 8080 端口（与原 Spring Boot 一致），可通过 PORT 环境变量修改。
APP 端只需将后端地址指向此服务即可。

支持的接口:
  POST /api/v1/detect             同步检测
  POST /api/v1/detect/async       异步检测
  GET  /api/v1/detect/<taskId>/status  查询异步任务结果
"""

import os
import json
import uuid
from datetime import datetime, timezone, timedelta
from threading import Lock
from flask import Flask, request, jsonify

app = Flask("mock-backend")

# ---------------------------------------------------------------------------
# 固定返回结果 — 改这里调整风控等级
# ---------------------------------------------------------------------------
FIXED_RESULT = {
    "riskLevel": "MEDIUM",
    "reason": "检测到疑似违规内容，触发中等风险规则",
    "score": 0.65,
    "transcription": "mock transcription 中危测试",
}

# ---------------------------------------------------------------------------
# 内存任务存储（异步接口用）
# ---------------------------------------------------------------------------
_tasks: dict = {}
_lock = Lock()
TZ = timezone(timedelta(hours=8))  # Asia/Shanghai


def _now():
    return datetime.now(TZ).isoformat()


def _ok(data=None, code=200):
    return jsonify({"code": code, "message": "success", "data": data})


def _err(msg, code=400):
    return jsonify({"code": code, "message": msg}), code


# ---------------------------------------------------------------------------
# POST /api/v1/detect  (同步检测)
# ---------------------------------------------------------------------------
@app.route("/api/v1/detect", methods=["POST"])
def detect():
    device_id = request.form.get("deviceId", "").strip()
    author_id = request.form.get("authorId", "").strip()
    video = request.files.get("video")

    # —— 参数校验 ——
    if not device_id:
        return _err("deviceId 不能为空")
    if not author_id:
        return _err("authorId 不能为空")
    if video is None or video.filename == "":
        return _err("video 文件不能为空")

    print(f"[detect] deviceId={device_id} authorId={author_id} "
          f"file={video.filename} size={len(video.read())}B  → SAFE")

    return _ok(FIXED_RESULT)


# ---------------------------------------------------------------------------
# POST /api/v1/detect/async  (异步检测)
# ---------------------------------------------------------------------------
@app.route("/api/v1/detect/async", methods=["POST"])
def detect_async():
    device_id = request.form.get("deviceId", "").strip()
    author_id = request.form.get("authorId", "").strip()
    video = request.files.get("video")

    if not device_id:
        return _err("deviceId 不能为空")
    if not author_id:
        return _err("authorId 不能为空")
    if video is None or video.filename == "":
        return _err("video 文件不能为空")

    task_id = uuid.uuid4().hex
    now = _now()
    with _lock:
        _tasks[task_id] = {
            "taskId": task_id,
            "status": "DONE",
            "result": FIXED_RESULT,
            "createdAt": now,
            "updatedAt": now,
        }

    print(f"[detect/async] deviceId={device_id} authorId={author_id} "
          f"file={video.filename} taskId={task_id}")

    return jsonify({
        "code": 202,
        "message": "任务已提交",
        "data": {
            "taskId": task_id,
            "status": "PENDING",
            "createdAt": now,
        },
    })


# ---------------------------------------------------------------------------
# GET /api/v1/detect/<taskId>/status  (查询异步结果)
# ---------------------------------------------------------------------------
@app.route("/api/v1/detect/<task_id>/status", methods=["GET"])
def task_status(task_id):
    with _lock:
        task = _tasks.get(task_id)
    if task is None:
        return _err("任务不存在或已过期", 404)
    return _ok(task)


# ---------------------------------------------------------------------------
# 健康检查（可选）
# ---------------------------------------------------------------------------
@app.route("/actuator/health", methods=["GET"])
def health():
    return jsonify({"status": "UP"})


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------
if __name__ == "__main__":
    port = int(os.environ.get("PORT", 8080))
    print(f"Mock backend running on http://localhost:{port}")
    print(f"  POST /api/v1/detect")
    print(f"  POST /api/v1/detect/async")
    print(f"  GET  /api/v1/detect/<taskId>/status")
    app.run(host="0.0.0.0", port=port, debug=False)
