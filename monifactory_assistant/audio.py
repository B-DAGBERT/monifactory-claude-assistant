"""Enregistrement, transcription locale et lecture vocale."""

import glob
import os
import queue
import re
import subprocess
import sys
import tempfile
import threading
import traceback
from pathlib import Path

from monifactory_assistant.paths import PACKAGE_ROOT
from monifactory_assistant.runtime import drain, log
from monifactory_assistant.settings import (
    NO_WINDOW,
    SAMPLE_RATE,
    VOICE,
    VOICE_SAMPLE_RATE,
    VOICE_SPEED,
    WHISPER_HINT,
    WHISPER_MODEL,
)


class Recorder:
    def __init__(self):
        try:
            import numpy
            import sounddevice

            self.np, self.sd = numpy, sounddevice
        except ImportError:
            self.np = self.sd = None
        self.stream = None
        self.frames = []

    @property
    def available(self):
        return self.sd is not None

    def start(self):
        if not self.available:
            return
        self.frames = []
        try:
            self.stream = self.sd.InputStream(
                samplerate=SAMPLE_RATE,
                channels=1,
                dtype="float32",
                callback=lambda data, *_: self.frames.append(data.copy()),
            )
            self.stream.start()
        except Exception:
            log("Micro indisponible :\n" + traceback.format_exc())
            self.stream = None

    def stop(self):
        if self.stream is None:
            return None
        self.stream.stop()
        self.stream.close()
        self.stream = None
        return self.np.concatenate(self.frames)[:, 0] if self.frames else None


class Transcriber:
    """Reconnaissance vocale locale (Whisper). Sur la carte graphique NVIDIA quand c'est possible (une phrase en
    moins d'une seconde), sinon sur le processeur, réglé pour aller vite."""

    def __init__(self):
        self.model = None
        self.error = None
        self.gpu = False
        self.ready = threading.Event()
        threading.Thread(target=self._load, daemon=True).start()

    def _load(self):
        try:
            # Preserve the speech runtime's original lazy import order.
            # isort: off
            from faster_whisper import WhisperModel
            import numpy
            # isort: on

            silence = numpy.zeros(SAMPLE_RATE // 2, dtype=numpy.float32)
            if self._cuda_ready():
                try:
                    model = WhisperModel(WHISPER_MODEL, device="cuda", compute_type="float16")
                    list(
                        model.transcribe(silence, language="fr", without_timestamps=True)[0]
                    )  # charge CUDA
                    list(
                        model.transcribe(silence, language="fr", vad_filter=True)[0]
                    )  # et le tri voix/silence
                    self.model, self.gpu = model, True
                except Exception as e:
                    log(
                        f"Reconnaissance vocale sur la carte graphique impossible ({e}), sur le processeur"
                    )
            if self.model is None:
                self.model = WhisperModel(
                    WHISPER_MODEL,
                    device="cpu",
                    compute_type="int8",
                    cpu_threads=min(8, os.cpu_count() or 4),
                )
                list(
                    self.model.transcribe(silence, language="fr", beam_size=1)[0]
                )  # la première est plus lente
                list(self.model.transcribe(silence, language="fr", vad_filter=True)[0])
            log(
                f"Reconnaissance vocale prête ({'carte graphique' if self.gpu else 'processeur'}, {WHISPER_MODEL})"
            )
        except Exception as e:
            self.error = e
            log("Reconnaissance vocale indisponible :\n" + traceback.format_exc())
        self.ready.set()

    @staticmethod
    def _cuda_ready():
        """Une carte NVIDIA, et cuBLAS installé (pip install nvidia-cublas-cu12) : on le rend trouvable."""
        try:
            import ctranslate2

            if ctranslate2.get_cuda_device_count() == 0:
                return False
        except Exception:
            return False
        for folder in glob.glob(
            os.path.join(sys.prefix, "Lib", "site-packages", "nvidia", "*", "bin")
        ):
            os.add_dll_directory(folder)
            os.environ["PATH"] = folder + os.pathsep + os.environ.get("PATH", "")
        return True

    def transcribe(self, audio, names=()):
        """names : les noms d'objets du moment (ceux de l'objectif), donnés à Whisper pour qu'il les reconnaisse."""
        self.ready.wait()
        if self.model is None:
            raise RuntimeError(
                f"Reconnaissance vocale indisponible ({self.error}). Tape ta question."
            )
        hint = WHISPER_HINT + (" " + ", ".join(names)[:400] + "." if names else "")
        # Sur le processeur, une seule hypothèse à la fois (beam 1) : presque aussi juste, deux fois plus rapide
        segments, _ = self.model.transcribe(
            audio,
            language="fr",
            vad_filter=True,
            initial_prompt=hint,
            beam_size=5 if self.gpu else 1,
            condition_on_previous_text=False,
            without_timestamps=True,
        )
        return " ".join(s.text.strip() for s in segments).strip()


def neural_voice(text):
    """Synthèse avec une voix neuronale Microsoft (service de lecture à voix haute d'Edge), en échantillons mono."""
    import asyncio
    import io

    import av
    import edge_tts
    import numpy as np

    async def synthesize():
        mp3 = bytearray()
        async for chunk in edge_tts.Communicate(text, VOICE, rate=VOICE_SPEED).stream():
            if chunk["type"] == "audio":
                mp3.extend(chunk["data"])
        return bytes(mp3)

    container = av.open(io.BytesIO(asyncio.run(synthesize())))
    resampler = av.AudioResampler(format="flt", layout="mono", rate=VOICE_SAMPLE_RATE)
    samples = []
    for frame in container.decode(audio=0):
        samples.extend(f.to_ndarray()[0] for f in resampler.resample(frame))
    samples.extend(f.to_ndarray()[0] for f in resampler.resample(None))
    return np.concatenate(samples)


class Speaker:
    """Lit les phrases à la suite : la suivante est synthétisée pendant que la précédente est lue.
    Voix neuronale gratuite (il faut Internet), voix Windows en secours."""

    def __init__(self):
        self.proc = None  # voix Windows de secours
        self.generation = 0  # chaque stop() annule les phrases en attente
        self.texts = queue.Queue()
        self.sounds = queue.Queue()
        threading.Thread(target=self._synthesize_loop, daemon=True).start()
        threading.Thread(target=self._play_loop, daemon=True).start()

    def say(self, text):
        self.stop()
        self.add(text)

    def add(self, text):
        text = re.sub(r"[*#`_>|]", "", text).strip()
        if text:
            self.texts.put((self.generation, text))

    def stop(self):
        self.generation += 1
        drain(self.texts)
        drain(self.sounds)
        try:
            import sounddevice

            sounddevice.stop()
        except Exception:
            pass
        if self.proc and self.proc.poll() is None:
            self.proc.kill()

    def _synthesize_loop(self):
        while True:
            generation, text = self.texts.get()
            if generation != self.generation:
                continue
            try:
                self.sounds.put((generation, neural_voice(text), text))
            except Exception:
                log(
                    "Voix neuronale indisponible, voix Windows à la place :\n"
                    + traceback.format_exc()
                )
                self.sounds.put((generation, None, text))

    def _play_loop(self):
        while True:
            generation, audio, text = self.sounds.get()
            if generation != self.generation:
                continue
            try:
                if audio is not None:
                    import sounddevice

                    sounddevice.play(audio, VOICE_SAMPLE_RATE)
                    sounddevice.wait()
                else:
                    self._windows_voice(text)
            except Exception:
                log(traceback.format_exc())

    def _windows_voice(self, text):
        if sys.platform == "darwin":
            self.proc = subprocess.Popen(["/usr/bin/say", "-v", "Amélie", "-r", "175", text])
            self.proc.wait()
            return
        text_file = Path(tempfile.gettempdir()) / "assistant_mc_parole.txt"
        text_file.write_text(text, encoding="utf-8")
        self.proc = subprocess.Popen(
            [
                "powershell",
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                str(PACKAGE_ROOT / "platform/parler.ps1"),
                str(text_file),
            ],
            creationflags=NO_WINDOW,
        )
        self.proc.wait()
