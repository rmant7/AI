# model-catalog

`local-models.json` is **generated**, not hand-edited: today's hard-coded
model lists (`LocalModels`, `TranslationModels`, `WhisperModels`,
`VoskModels`, `ExperimentalEmbeddingModels`) described in the `:model-core`
domain by `LegacyCatalogMapper`.

- Every entry is `unverified`: nothing is pinned to a commit or hash-checked.
  GGUF entries keep their install-time selection
  (`huggingface_selection` — repositories tried in order on `main`, file
  picked by quantization priority), which is exactly what the app does today.
- The app does not read this file yet, and it is not packaged into the APK.
- `LegacyCatalogSnapshotTest` fails when this file is stale. Regenerate with

      ./gradlew :app:testDebugUnitTest -PupdateCatalogSnapshot=true

  and review the diff. Whether the content is right is checked separately,
  seed by seed, by `LegacyCatalogGoldenTest`.
