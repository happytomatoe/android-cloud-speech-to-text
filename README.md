# Android Cloud Speech-to-Text

Android Cloud Speech-to-Text is a speech-to-text Android keyboard based on [Whisper To Input](https://github.com/j3soon/whisper-to-input) by Johnson Sun (@j3soon). It performs speech-to-text (STT/ASR) with OpenAI Whisper and inputs the recognized text. Supports English, Chinese, Japanese, and even mixed languages and Taiwanese.

This is a fork of [whisper-to-input](https://github.com/j3soon/whisper-to-input) with additional features and modifications.

**Repository**: https://github.com/happytomatoe/android-cloud-speech-to-text

## Features

- Multiple STT backends: OpenAI API, Whisper ASR Webservice, NVIDIA NIM, Deepgram, Groq, ElevenLabs Scribe, Voxtral (Mistral), and more
- Real-time transcription
- Custom keyboard layout with microphone, backspace, enter, and settings keys
- Support for multiple languages including Taiwanese (Hokkien)

## Installation

1. Download the APK file from [the latest release](https://github.com/happytomatoe/android-cloud-speech-to-text/releases/latest) to your phone.

2. Locate the APK file in your phone and click it. Click "Install" to install the app.

3. An `Unsafe app blocked` warning may pop up. Click `More details` and then click `Install anyway`. Click `Open` to open the app.

4. Allow the app to record audio and send notifications. These permissions are required for the app to work properly.

5. Go to the app settings page and enter your configuration. You can use the official OpenAI API with [your API key](https://platform.openai.com/api-keys) or self-host a [Whisper ASR Webservice](https://github.com/ahmetoner/whisper-asr-webservice). For more information, see the [Services](#services) section.

6. Go to the system settings page and enable the app keyboard.

7. Open any app that requires text input, such as a browser, and click the input box. Choose the app keyboard by clicking the bottom right button and choosing `Cloud Speech-to-Text`.

8. Click the microphone button to start recording. After you finish speaking, click the microphone button again. The recognized text will be inputted into the text box.

## Keyboard Usage

- `Microphone Key` in the center: Click to start recording, click again to stop recording, and input the recognized text.
- `Cancel Key` in the bottom left (Only visible when recording): Click to cancel the current recording.
- `Backspace Key` in the upper right: Delete the previous character.
- `Enter Key` in the bottom right: Input a newline character.
- `Settings Key` in the upper left: Open the app settings page.
- `Switch Key` in the upper left: Switch to the previous input method.

## Services

### OpenAI API

Requires an [OpenAI API key](https://platform.openai.com/api-keys).

- Endpoint: `https://api.openai.com/v1/audio/transcriptions`
- Model: `whisper-1`

### Whisper ASR Webservice

Self-hosted whisper service. Requires a self-hosted server.

[Whisper ASR Webservice](https://github.com/ahmetoner/whisper-asr-webservice) can be set up as described in [#13](https://github.com/j3soon/whisper-to-input/pull/13).

### NVIDIA NIM

NVIDIA's optimized whisper model using TensorRT-LLM. Requires a self-hosted server.

See the [NVIDIA NIM documentation](https://build.nvidia.com/openai/whisper-large-v3) for deployment instructions.

### Deepgram

Cloud-based speech-to-text API.

- Endpoint: `https://api.deepgram.com/v1/listen`
- Model: `nova-3`

### Groq

Cloud-based Whisper transcription API.

- Endpoint: `https://api.groq.com/openai/v1/audio/transcriptions`
- Model: `whisper-large-v3-turbo`

### ElevenLabs Scribe

Cloud-based speech-to-text API.

- Endpoint: `https://api.elevenlabs.io/v1/speech-to-text`
- Model: `scribe_v1`

### Voxtral (Mistral AI)

Cloud-based speech-to-text API from Mistral AI.

- Endpoint: `https://api.mistral.ai/v1/audio/transcriptions`
- Model: `voxtral-mini-latest`

## Credits

This project is based on [Whisper To Input](https://github.com/j3soon/whisper-to-input) by:
- Yan-Bin Diau ([@tigerpaws01](https://github.com/tigerpaws01))
- Johnson Sun ([@j3soon](https://github.com/j3soon))
- Ying-Chou Sun ([@ijsun](https://github.com/ijsun))

## License

This repository is licensed under the GPLv3 license. For more information, please refer to the [LICENSE](android/LICENSE) file.
