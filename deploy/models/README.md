# Whisper 模型目录

把 whisper.cpp 的 GGML 多语言模型放在此目录，Compose 会以只读方式挂载到 `/models`。

```bash
curl -fL --retry 3 \
  -o deploy/models/ggml-base.bin \
  https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin
```

模型文件已被 `.gitignore` 忽略。文件名需与 `deploy/.env` 的 `WHISPER_MODEL_FILE` 一致。
