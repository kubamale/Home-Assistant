# Wake word models

The door screen's wake word ("Hey Jarvis") is detected on the device with three small ONNX models from
[openWakeWord](https://github.com/dscripka/openWakeWord), release **v0.5.1**
(`https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/<file>`):

| File | Size | SHA-256 |
|---|---|---|
| `melspectrogram.onnx` | 1,087,958 | `ba2b0e0f8b7b875369a2c89cb13360ff53bac436f2895cced9f479fa65eb176f` |
| `embedding_model.onnx` | 1,326,578 | `70d164290c1d095d1d4ee149bc5e00543250a7316b59f31d056cff7bd3075c1f` |
| `hey_jarvis_v0.1.onnx` | 1,271,370 | `94a13cfe60075b132f6a472e7e462e8123ee70861bc3fb58434a73712ee0d2cb` |

**Licence.** openWakeWord's code is Apache 2.0, but its pre-trained models (these three) are licensed
**Creative Commons Attribution-NonCommercial-ShareAlike 4.0**. That is fine for a home project; it does not allow selling
them or a product that contains them. To use another wake word, train one with openWakeWord (or take another of its
pre-trained ones), put the file here and change `MODELS` in `src/voice/wakeDetector.ts`.
