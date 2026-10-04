Legal deterministic test fixtures generated locally with FFmpeg from lavfi sine
(440 Hz, two seconds) and a solid blue 32x32 video for tone.mp4. No downloaded
or copyrighted recordings. FFmpeg is a fixture-generation tool only and is not
an app/build dependency. M4A: AAC 44.1 kHz stereo, WebM: Opus 48 kHz stereo,
MP4: MPEG4 blue video + AAC 44.1 kHz mono, MP3: CBR LAME 192 kbps stereo with
Xing writing disabled, AAC: ADTS 44.1 kHz mono. Encoding tests use the app's
actual native LAME bridge and Android decoder, never FFmpeg at runtime.
