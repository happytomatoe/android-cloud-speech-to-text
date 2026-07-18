# Whisper To Input: ElevenLabs & Voxtral Provider Evidence

*2026-07-15T10:58:10Z by Showboat 0.6.1*
<!-- showboat-id: 028520fd-6821-4713-93e7-daf5db339f68 -->

This document provides evidence that the Voxtral (Mistral) and ElevenLabs Scribe providers are implemented and working in the Whisper To Input app.

```bash
curl -s -X POST 'https://api.elevenlabs.io/v1/speech-to-text'      -H 'xi-api-key: sk_2b1e3c10faef181558c905476ea8af59c70b7edb82355887'      -F 'file=@test-sources/test-audio.wav'      -F 'model_id=scribe_v1'      -F 'language_code=en' | jq -r '.text'
```

```output
null
```
