# Screen translation

Coffee GB can translate foreign text on a game screen into English using the
OpenAI API. Press **Ctrl+Shift+T** on Windows or Linux, **Command+Shift+T** on
macOS, or choose **Game > Translate Screen**.

The game pauses while Coffee GB captures the screen and requests a translation.
English text appears over the original text at the positions returned by the
model. Press the shortcut again or **Esc** to dismiss the translation and
continue. A game that was already paused remains paused when translation closes.
Translation is unavailable during netplay.

## Setup

Open **File > Preferences… > Translation**, enter your **OpenAI API key**, and
choose **Save changes**. The field is masked by default; **Show API key** reveals
it while editing. The saved key takes effect on the next translation request,
without restarting Coffee GB. **Cancel** discards edits.

The key is saved locally with your application settings, without encryption.
On POSIX filesystems, Coffee GB restricts settings containing a key to owner read
and write access. If Preferences reports session-only settings, the key is also
used only for that session.

Use **Clear** and save to remove the saved key. An empty field falls back to the
`OPENAI_API_KEY` environment variable. Coffee GB never copies that environment
value into the preferences field or settings file. To use this fallback, set the
variable before starting Coffee GB so the application inherits it.

For example, to use the environment fallback with the portable JAR on Linux or macOS:

```sh
export OPENAI_API_KEY='your-api-key'
java -jar coffee-gb-VERSION.jar
```

Or in Windows PowerShell:

```powershell
$env:OPENAI_API_KEY = 'your-api-key'
java -jar coffee-gb-VERSION.jar
```

Replace `coffee-gb-VERSION.jar` with the downloaded file's name. The same
environment variable works with the native application when its launcher
inherits the variable.

Coffee GB uses `gpt-4.1-mini` by default. Set `COFFEE_GB_TRANSLATION_MODEL` before
launch to select another OpenAI model that accepts images and supports strict
JSON Schema structured outputs. Larger models may exceed the translation
deadline. See OpenAI's [GPT-4.1 mini documentation](https://developers.openai.com/api/docs/models/gpt-4.1-mini),
[image input guide](https://developers.openai.com/api/docs/guides/images-vision),
and [structured outputs guide](https://developers.openai.com/api/docs/guides/structured-outputs).

## What is sent

Each explicit translation request sends a screenshot of the game display to
OpenAI. Coffee GB does not send the ROM, save files, or filesystem paths. It does
not translate continuously in the background. The API key, request images, and
responses are not written to application logs or a translation cache on disk.
OpenAI API usage is billed to the account associated with the key.

The API returns English text and bounding boxes, and Coffee GB draws the text
over the captured screen. English text and non-text artwork should remain as
they were. Small pixel fonts, vertical writing, and unusual lettering can cause
recognition, translation, or placement mistakes.

## Timing and failures

Coffee GB gives each translation a nine-second deadline and does not retry
automatically. This keeps waiting bounded below the ten-second target; it cannot
guarantee that OpenAI will successfully translate every screen within that time.
The request runs off the desktop UI thread, so the window remains responsive.
Repeat the shortcut or press **Esc** to cancel a pending request.

If the request times out, fails, or finds no foreign text, Coffee GB shows a
status message. For a timeout, dismiss the message and trigger translation again
to make a new request. For authentication or account errors, check the key and
the OpenAI account's API access. Correct a saved key in **Preferences > Translation**
and try again; changing the environment fallback requires relaunching Coffee GB.
