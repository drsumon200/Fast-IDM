import os
import re
import sys
import json
import time
import shutil
import platform
import threading
import subprocess
from urllib.parse import urlparse, unquote
from concurrent.futures import ThreadPoolExecutor
from http.server import HTTPServer, BaseHTTPRequestHandler

import requests
import yt_dlp
import tkinter as tk
from tkinter import ttk, filedialog, messagebox

HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/128.0.0.0 Safari/537.36"
    )
}

STREAMING_DOMAINS = (
    "youtube.com", "youtu.be", "facebook.com", "fb.watch", "fb.com",
    "instagram.com", "tiktok.com", "twitter.com", "x.com", "vimeo.com",
    "dailymotion.com", "reddit.com", "twitch.tv", "soundcloud.com",
    "bilibili.com", "rumble.com"
)


class _PauseSignal(Exception):
    pass


class _CancelSignal(Exception):
    pass


class _SilentLogger:
    def debug(self, msg):
        pass

    def warning(self, msg):
        pass

    def error(self, msg):
        pass


def get_app_dir():
    if getattr(sys, "frozen", False):
        return os.path.dirname(sys.executable)
    return os.path.dirname(os.path.abspath(__file__))


def find_ffmpeg_dir():
    app_dir = get_app_dir()
    exe_name = "ffmpeg.exe" if platform.system() == "Windows" else "ffmpeg"
    local_ffmpeg = os.path.join(app_dir, exe_name)
    if os.path.isfile(local_ffmpeg):
        return app_dir
    sys_ffmpeg = shutil.which("ffmpeg")
    if sys_ffmpeg:
        return os.path.dirname(sys_ffmpeg)
    return None


def is_streaming_url(url):
    try:
        host = urlparse(url).netloc.lower()
        return any(host == d or host.endswith("." + d) for d in STREAMING_DOMAINS)
    except Exception:
        return False


def format_bytes(size):
    if size is None or size < 0:
        return "Unknown"
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if size < 1024:
            return f"{size:.2f} {unit}" if unit != "B" else f"{int(size)} B"
        size /= 1024
    return f"{size:.2f} PB"


def format_eta(seconds):
    if seconds is None or seconds < 0 or seconds > 86400 * 7:
        return "--:--"
    seconds = int(seconds)
    h, rem = divmod(seconds, 3600)
    m, s = divmod(rem, 60)
    return f"{h:02d}:{m:02d}:{s:02d}" if h > 0 else f"{m:02d}:{s:02d}"


def extract_filename(url, headers):
    cd = headers.get("Content-Disposition", "")
    name = ""
    if cd:
        match = re.search(r"filename\*=UTF-8''([^;]+)", cd, re.IGNORECASE)
        if match:
            name = unquote(match.group(1)).strip('"\'')
        else:
            match = re.search(r'filename="?([^";]+)"?', cd, re.IGNORECASE)
            if match:
                name = unquote(match.group(1)).strip('"\'')

    if not name:
        path = unquote(urlparse(url).path)
        name = os.path.basename(path)

    name = re.sub(r'[<>:"/\\|?*]', "_", name).strip()
    return name if name else "downloaded_file"


def get_unique_filepath(folder, filename):
    base, ext = os.path.splitext(filename)
    filepath = os.path.join(folder, filename)
    counter = 1
    while os.path.exists(filepath):
        filepath = os.path.join(folder, f"{base} ({counter}){ext}")
        counter += 1
    return filepath


class DownloadTask:
    def __init__(self, task_id, url, save_dir, num_threads=8, quality="Best MP4", referer="", force_ytdlp=False):
        self.task_id = task_id
        self.url = url.strip()
        self.save_dir = save_dir
        self.num_threads = max(1, int(num_threads))
        self.quality = quality
        self.referer = referer
        self.engine = "ytdlp" if (force_ytdlp or is_streaming_url(self.url)) else "auto"

        self.filename = "Connecting..."
        self.filepath = ""
        self.total_size = -1
        self.supports_range = False
        self.status = "Starting..."
        self.error_msg = ""

        # HTTP engine state
        self.chunks = []
        self.chunk_progress = []

        # yt-dlp engine state
        self.yt_downloaded = 0

        self.speed = 0.0
        self.eta = None
        self._last_bytes = 0
        self._last_time = time.time()

        self.pause_event = threading.Event()
        self.cancel_event = threading.Event()
        self.worker_thread = None

    def _get_headers(self):
        h = dict(HEADERS)
        if self.referer:
            h["Referer"] = self.referer
        return h

    def get_downloaded(self):
        if self.engine == "ytdlp":
            return self.yt_downloaded
        return sum(self.chunk_progress)

    def get_progress_percent(self):
        if self.status == "Completed":
            return 100.0
        if self.total_size > 0:
            return min(100.0, (self.get_downloaded() / self.total_size) * 100.0)
        return 0.0

    def update_speed_and_eta(self):
        if self.engine == "ytdlp":
            if self.status != "Downloading":
                self.speed = 0.0
                self.eta = None
            return

        now = time.time()
        dt = now - self._last_time
        if dt >= 0.4:
            current = self.get_downloaded()
            if self.status == "Downloading":
                instant = max(0.0, (current - self._last_bytes) / dt)
                self.speed = instant if self.speed == 0 else (0.6 * instant + 0.4 * self.speed)
                if self.speed > 0 and self.total_size > 0:
                    self.eta = max(0, self.total_size - current) / self.speed
                else:
                    self.eta = None
            else:
                self.speed = 0.0
                self.eta = None
            self._last_bytes = current
            self._last_time = now

    def start(self):
        if self.worker_thread and self.worker_thread.is_alive():
            return
        self.pause_event.clear()
        self.cancel_event.clear()
        self.error_msg = ""
        self._last_time = time.time()
        self._last_bytes = self.get_downloaded()
        self.worker_thread = threading.Thread(target=self._run, daemon=True)
        self.worker_thread.start()

    def pause(self):
        if self.status in ("Downloading", "Starting...", "Probing...", "Fetching info..."):
            self.pause_event.set()
            self.status = "Pausing..."

    def resume(self):
        if self.status in ("Paused", "Error"):
            self.status = "Resuming..."
            self.start()

    def cancel(self):
        self.cancel_event.set()
        self.pause_event.set()
        self.status = "Cancelled"
        threading.Thread(target=self._cleanup_after_cancel, daemon=True).start()

    def _cleanup_after_cancel(self):
        if self.worker_thread and self.worker_thread.is_alive():
            self.worker_thread.join(timeout=6)
        self._delete_temp_files()

    def _delete_temp_files(self):
        for _, _, part_path in self.chunks:
            try:
                if os.path.exists(part_path):
                    os.remove(part_path)
            except OSError:
                pass
        if self.engine == "ytdlp" and self.filepath:
            for candidate in (self.filepath + ".part", self.filepath + ".ytdl"):
                try:
                    if os.path.exists(candidate):
                        os.remove(candidate)
                except OSError:
                    pass

    # ---------------- YT-DLP ENGINE (YouTube, Facebook, 1000+ sites) ----------------
    def _build_ytdlp_format(self, ffmpeg_dir):
        q = self.quality
        if q == "Audio (MP3)":
            return "bestaudio/best" if ffmpeg_dir else "bestaudio[ext=m4a]/bestaudio/best"

        height_map = {"1080p": 1080, "720p": 720, "480p": 480, "360p": 360}
        if q in height_map:
            h = height_map[q]
            if ffmpeg_dir:
                return (
                    f"bestvideo[height<={h}][ext=mp4]+bestaudio[ext=m4a]/"
                    f"bestvideo[height<={h}]+bestaudio/best[height<={h}]/best"
                )
            return f"best[height<={h}][ext=mp4]/best[height<={h}]/best"

        # Best MP4
        if ffmpeg_dir:
            return "bestvideo[ext=mp4]+bestaudio[ext=m4a]/bestvideo+bestaudio/best"
        return "best[ext=mp4]/best"

    def _ytdlp_progress_hook(self, d):
        if self.cancel_event.is_set():
            raise _CancelSignal()
        if self.pause_event.is_set():
            raise _PauseSignal()

        fp = d.get("filename")
        if fp:
            self.filepath = fp
            self.filename = os.path.basename(fp)

        st = d.get("status")
        if st == "downloading":
            self.status = "Downloading"
            self.yt_downloaded = d.get("downloaded_bytes") or self.yt_downloaded
            total = d.get("total_bytes") or d.get("total_bytes_estimate")
            if total:
                self.total_size = int(total)
            self.speed = d.get("speed") or 0.0
            self.eta = d.get("eta")
        elif st == "finished":
            self.status = "Merging / Processing..."
            self.speed = 0.0
            self.eta = 0

    def _ytdlp_pp_hook(self, d):
        if d.get("status") == "finished":
            fp = d.get("info_dict", {}).get("filepath")
            if fp:
                self.filepath = fp
                self.filename = os.path.basename(fp)

    def _run_ytdlp(self):
        self.engine = "ytdlp"
        self.status = f"Fetching ({self.quality})..."
        ffmpeg_dir = find_ffmpeg_dir()

        ydl_opts = {
            "outtmpl": os.path.join(self.save_dir, "%(title).110s [%(id)s].%(ext)s"),
            "windowsfilenames": True,
            "format": self._build_ytdlp_format(ffmpeg_dir),
            "continuedl": True,
            "noplaylist": True,
            "concurrent_fragment_downloads": min(self.num_threads, 8),
            "retries": 5,
            "fragment_retries": 5,
            "quiet": True,
            "no_warnings": True,
            "noprogress": True,
            "logger": _SilentLogger(),
            "progress_hooks": [self._ytdlp_progress_hook],
            "postprocessor_hooks": [self._ytdlp_pp_hook],
        }

        if ffmpeg_dir:
            ydl_opts["ffmpeg_location"] = ffmpeg_dir
            if self.quality == "Audio (MP3)":
                ydl_opts["postprocessors"] = [{
                    "key": "FFmpegExtractAudio",
                    "preferredcodec": "mp3",
                    "preferredquality": "192",
                }]
            else:
                ydl_opts["merge_output_format"] = "mp4"

        try:
            with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                info = ydl.extract_info(self.url, download=True)
                if info:
                    req = info.get("requested_downloads")
                    if req and isinstance(req, list) and req[0].get("filepath"):
                        self.filepath = req[0]["filepath"]
                    else:
                        self.filepath = ydl.prepare_filename(info)
                    self.filename = os.path.basename(self.filepath)

            if self.filepath and os.path.exists(self.filepath):
                final_size = os.path.getsize(self.filepath)
                self.total_size = final_size
                self.yt_downloaded = final_size

            self.status = "Completed"
        except _CancelSignal:
            self.status = "Cancelled"
            self._delete_temp_files()
        except _PauseSignal:
            self.status = "Paused"
        except Exception as e:
            if self.cancel_event.is_set():
                self.status = "Cancelled"
            elif self.pause_event.is_set():
                self.status = "Paused"
            else:
                self.status = "Error"
                self.error_msg = str(e)

    # ---------------- MULTI-THREADED HTTP ENGINE (Direct Files) ----------------
    def _probe_http(self):
        self.status = "Probing..."
        probe_headers = self._get_headers()
        probe_headers["Range"] = "bytes=0-0"

        with requests.get(self.url, headers=probe_headers, stream=True, timeout=20, allow_redirects=True) as resp:
            ctype = resp.headers.get("Content-Type", "").lower()
            cd = resp.headers.get("Content-Disposition", "").lower()

            if "text/html" in ctype and "attachment" not in cd:
                return False

            if resp.status_code == 416:
                with requests.get(self.url, headers=self._get_headers(), stream=True, timeout=20, allow_redirects=True) as r2:
                    r2.raise_for_status()
                    if "text/html" in r2.headers.get("Content-Type", "").lower() and "attachment" not in r2.headers.get("Content-Disposition", "").lower():
                        return False
                    self.filename = extract_filename(r2.url, r2.headers)
                    cl = r2.headers.get("Content-Length")
                    self.total_size = int(cl) if cl and cl.isdigit() else -1
                    self.supports_range = False
            else:
                resp.raise_for_status()
                self.filename = extract_filename(resp.url, resp.headers)

                if resp.status_code == 206:
                    self.supports_range = True
                    content_range = resp.headers.get("Content-Range", "")
                    match = re.search(r"/(\d+)$", content_range)
                    if match:
                        self.total_size = int(match.group(1))
                    else:
                        self.supports_range = False
                else:
                    self.supports_range = False
                    cl = resp.headers.get("Content-Length")
                    self.total_size = int(cl) if cl and cl.isdigit() else -1

        self.engine = "http"
        self.filepath = get_unique_filepath(self.save_dir, self.filename)
        self.filename = os.path.basename(self.filepath)

        if self.supports_range and self.total_size > 512 * 1024:
            threads = min(self.num_threads, max(1, self.total_size // (256 * 1024)))
            chunk_size = self.total_size // threads
            self.chunks = []
            for i in range(threads):
                start = i * chunk_size
                end = (self.total_size - 1) if i == threads - 1 else (start + chunk_size - 1)
                self.chunks.append((start, end, f"{self.filepath}.part{i}"))
        elif self.supports_range and self.total_size > 0:
            self.chunks = [(0, self.total_size - 1, f"{self.filepath}.part0")]
        else:
            self.chunks = [(0, -1, f"{self.filepath}.part0")]

        self.chunk_progress = [0] * len(self.chunks)
        return True

    def _download_chunk(self, idx):
        start, end, part_path = self.chunks[idx]
        expected_len = (end - start + 1) if end >= 0 else -1
        existing = os.path.getsize(part_path) if os.path.exists(part_path) else 0
        if not self.supports_range:
            existing = 0

        if expected_len > 0 and existing >= expected_len:
            self.chunk_progress[idx] = expected_len
            return

        self.chunk_progress[idx] = existing
        retries = 3

        for attempt in range(retries):
            if self.pause_event.is_set() or self.cancel_event.is_set():
                return

            req_headers = self._get_headers()
            mode = "wb"
            if self.supports_range and end >= 0:
                current_start = start + self.chunk_progress[idx]
                req_headers["Range"] = f"bytes={current_start}-{end}"
                mode = "ab" if self.chunk_progress[idx] > 0 else "wb"

            try:
                with requests.get(self.url, headers=req_headers, stream=True, timeout=25) as resp:
                    if self.supports_range and resp.status_code not in (206, 200):
                        resp.raise_for_status()
                    elif not self.supports_range:
                        resp.raise_for_status()

                    if resp.status_code == 200 and self.supports_range and idx == 0:
                        mode = "wb"
                        self.chunk_progress[idx] = 0

                    with open(part_path, mode) as f:
                        for data in resp.iter_content(chunk_size=65536):
                            if self.pause_event.is_set() or self.cancel_event.is_set():
                                return
                            if data:
                                f.write(data)
                                self.chunk_progress[idx] += len(data)
                return
            except Exception as exc:
                if attempt == retries - 1:
                    raise exc
                time.sleep(1.5)

    def _merge_parts(self):
        self.status = "Merging..."
        if len(self.chunks) == 1:
            part_path = self.chunks[0][2]
            if os.path.exists(self.filepath):
                os.remove(self.filepath)
            os.replace(part_path, self.filepath)
            return

        with open(self.filepath, "wb") as outfile:
            for _, _, part_path in self.chunks:
                with open(part_path, "rb") as infile:
                    while True:
                        buf = infile.read(1024 * 1024)
                        if not buf:
                            break
                        outfile.write(buf)
        self._delete_temp_files()

    def _run(self):
        if self.engine == "ytdlp":
            self._run_ytdlp()
            return

        try:
            if not self.chunks:
                is_direct = self._probe_http()
                if not is_direct:
                    self._run_ytdlp()
                    return

            if self.cancel_event.is_set():
                self.status = "Cancelled"
                self._delete_temp_files()
                return
            if self.pause_event.is_set():
                self.status = "Paused"
                return

            self.status = "Downloading"
            with ThreadPoolExecutor(max_workers=len(self.chunks)) as pool:
                futures = [pool.submit(self._download_chunk, i) for i in range(len(self.chunks))]
                for f in futures:
                    f.result()

            if self.cancel_event.is_set():
                self.status = "Cancelled"
                self._delete_temp_files()
                return
            if self.pause_event.is_set():
                self.status = "Paused"
                return

            self._merge_parts()
            if self.total_size < 0 and os.path.exists(self.filepath):
                self.total_size = os.path.getsize(self.filepath)
            self.status = "Completed"

        except Exception as e:
            if self.cancel_event.is_set():
                self.status = "Cancelled"
            elif self.pause_event.is_set():
                self.status = "Paused"
            else:
                self.status = "Error"
                self.error_msg = str(e)


class DownloadManagerApp:
    def __init__(self, root):
        self.root = root
        self.root.title("Universal Download Manager")
        self.root.geometry("980x590")
        self.root.minsize(820, 480)
        self.root.configure(bg="#1e1e2e")

        self.tasks = {}
        self.next_id = 1
        self.default_dir = os.path.join(os.path.expanduser("~"), "Downloads")
        if not os.path.isdir(self.default_dir):
            self.default_dir = os.path.expanduser("~")

        self._setup_styles()
        self._build_ui()
        self._start_local_server()
        self._poll_ui()

    def _setup_styles(self):
        style = ttk.Style()
        style.theme_use("clam")

        bg, card, fg, accent = "#1e1e2e", "#2a2a3c", "#cdd6f4", "#89b4fa"
        style.configure("TFrame", background=bg)
        style.configure("Card.TFrame", background=card)
        style.configure("TLabel", background=bg, foreground=fg, font=("Segoe UI", 10))
        style.configure("Card.TLabel", background=card, foreground=fg, font=("Segoe UI", 10))
        style.configure("Muted.TLabel", background=bg, foreground="#a6adc8", font=("Segoe UI", 9))
        style.configure("Title.TLabel", background=bg, foreground=accent, font=("Segoe UI", 15, "bold"))

        style.configure(
            "Accent.TButton", background=accent, foreground="#11111b",
            font=("Segoe UI", 10, "bold"), padding=(14, 6), borderwidth=0,
        )
        style.map("Accent.TButton", background=[("active", "#74c7ec")])

        style.configure(
            "Action.TButton", background="#313244", foreground=fg,
            font=("Segoe UI", 9, "bold"), padding=(12, 6), borderwidth=0,
        )
        style.map("Action.TButton", background=[("active", "#45475a")])

        style.configure(
            "Treeview", background="#181825", foreground=fg, fieldbackground="#181825",
            rowheight=28, font=("Segoe UI", 9), borderwidth=0,
        )
        style.configure(
            "Treeview.Heading", background="#313244", foreground=accent,
            font=("Segoe UI", 9, "bold"), borderwidth=0,
        )
        style.map("Treeview", background=[("selected", "#45475a")])

        style.configure(
            "Custom.Horizontal.TProgressbar", troughcolor="#313244",
            background="#a6e3a1", thickness=14, borderwidth=0,
        )

    def _build_ui(self):
        header = ttk.Frame(self.root, padding=(16, 12, 16, 4))
        header.pack(fill=tk.X)
        ttk.Label(header, text="⚡ Universal Download Manager", style="Title.TLabel").pack(side=tk.LEFT)

        ffmpeg_status = (
            "FFmpeg: Ready (HD 1080p+/MP3)"
            if find_ffmpeg_dir()
            else "FFmpeg: Not found (Put ffmpeg.exe beside app for 1080p+/MP3)"
        )
        ttk.Label(header, text=f"Browser Bridge: Active  |  {ffmpeg_status}", style="Muted.TLabel").pack(side=tk.RIGHT, pady=(4, 0))

        card = ttk.Frame(self.root, style="Card.TFrame", padding=14)
        card.pack(fill=tk.X, padx=16, pady=(6, 10))

        url_row = ttk.Frame(card, style="Card.TFrame")
        url_row.pack(fill=tk.X, pady=(0, 8))
        ttk.Label(url_row, text="URL / Video:", style="Card.TLabel", width=12).pack(side=tk.LEFT)

        self.url_var = tk.StringVar()
        self.url_entry = tk.Entry(
            url_row, textvariable=self.url_var, bg="#181825", fg="#cdd6f4",
            insertbackground="#cdd6f4", relief=tk.FLAT, font=("Segoe UI", 10),
        )
        self.url_entry.pack(side=tk.LEFT, fill=tk.X, expand=True, ipady=5, padx=(0, 8))
        self.url_entry.bind("<Return>", lambda e: self.add_download())

        ttk.Button(url_row, text="Paste", style="Action.TButton", command=self.paste_url).pack(side=tk.LEFT, padx=(0, 6))
        ttk.Button(url_row, text="Download", style="Accent.TButton", command=self.add_download).pack(side=tk.LEFT)

        opt_row = ttk.Frame(card, style="Card.TFrame")
        opt_row.pack(fill=tk.X)
        ttk.Label(opt_row, text="Save To:", style="Card.TLabel", width=12).pack(side=tk.LEFT)

        self.dir_var = tk.StringVar(value=self.default_dir)
        tk.Entry(
            opt_row, textvariable=self.dir_var, bg="#181825", fg="#cdd6f4",
            insertbackground="#cdd6f4", relief=tk.FLAT, font=("Segoe UI", 9),
        ).pack(side=tk.LEFT, fill=tk.X, expand=True, ipady=4, padx=(0, 8))
        ttk.Button(opt_row, text="Browse", style="Action.TButton", command=self.browse_dir).pack(side=tk.LEFT, padx=(0, 12))

        ttk.Label(opt_row, text="Video Quality:", style="Card.TLabel").pack(side=tk.LEFT, padx=(0, 5))
        self.quality_var = tk.StringVar(value="Best MP4")
        ttk.Combobox(
            opt_row, textvariable=self.quality_var,
            values=["Best MP4", "1080p", "720p", "480p", "360p", "Audio (MP3)"],
            width=12, state="readonly"
        ).pack(side=tk.LEFT, padx=(0, 12))

        ttk.Label(opt_row, text="Threads:", style="Card.TLabel").pack(side=tk.LEFT, padx=(0, 5))
        self.threads_var = tk.StringVar(value="8")
        ttk.Combobox(
            opt_row, textvariable=self.threads_var,
            values=["1", "2", "4", "8", "16"], width=4, state="readonly"
        ).pack(side=tk.LEFT)

        table_frame = ttk.Frame(self.root, padding=(16, 0))
        table_frame.pack(fill=tk.BOTH, expand=True)

        columns = ("file", "engine", "size", "progress", "speed", "eta", "status")
        self.tree = ttk.Treeview(table_frame, columns=columns, show="headings", selectmode="browse")
        headers = [
            ("file", "File Name", 280, tk.W),
            ("engine", "Type", 70, tk.CENTER),
            ("size", "Size", 155, tk.CENTER),
            ("progress", "Progress", 80, tk.CENTER),
            ("speed", "Speed", 100, tk.CENTER),
            ("eta", "ETA", 75, tk.CENTER),
            ("status", "Status", 140, tk.CENTER),
        ]
        for col, text, width, anchor in headers:
            self.tree.heading(col, text=text)
            self.tree.column(col, width=width, anchor=anchor)

        scrollbar = ttk.Scrollbar(table_frame, orient=tk.VERTICAL, command=self.tree.yview)
        self.tree.configure(yscrollcommand=scrollbar.set)
        self.tree.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        scrollbar.pack(side=tk.RIGHT, fill=tk.Y)

        prog_frame = ttk.Frame(self.root, padding=(16, 10, 16, 4))
        prog_frame.pack(fill=tk.X)
        self.selected_label = ttk.Label(prog_frame, text="Paste any file, YouTube, or Facebook link — or hover over a video in your browser!")
        self.selected_label.pack(anchor=tk.W, pady=(0, 4))
        self.progress_bar = ttk.Progressbar(
            prog_frame, style="Custom.Horizontal.TProgressbar", mode="determinate", maximum=100
        )
        self.progress_bar.pack(fill=tk.X)

        ctrl = ttk.Frame(self.root, padding=(16, 8, 16, 14))
        ctrl.pack(fill=tk.X)
        ttk.Button(ctrl, text="⏸ Pause", style="Action.TButton", command=self.pause_selected).pack(side=tk.LEFT, padx=(0, 6))
        ttk.Button(ctrl, text="▶ Resume", style="Action.TButton", command=self.resume_selected).pack(side=tk.LEFT, padx=(0, 6))
        ttk.Button(ctrl, text="✖ Cancel / Remove", style="Action.TButton", command=self.remove_selected).pack(side=tk.LEFT, padx=(0, 6))
        ttk.Button(ctrl, text="📂 Open Folder", style="Action.TButton", command=self.open_folder).pack(side=tk.RIGHT)

    def paste_url(self):
        try:
            self.url_var.set(self.root.clipboard_get().strip())
        except tk.TclError:
            pass

    def browse_dir(self):
        chosen = filedialog.askdirectory(initialdir=self.dir_var.get())
        if chosen:
            self.dir_var.set(chosen)

    def _create_task(self, url, referer="", force_ytdlp=False, quality=None):
        save_dir = self.dir_var.get().strip()
        if not os.path.isdir(save_dir):
            save_dir = self.default_dir

        tid = str(self.next_id)
        self.next_id += 1

        task = DownloadTask(
            tid, url, save_dir,
            num_threads=int(self.threads_var.get()),
            quality=quality if quality else self.quality_var.get(),
            referer=referer,
            force_ytdlp=force_ytdlp,
        )
        self.tasks[tid] = task
        self.tree.insert("", tk.END, iid=tid, values=(task.filename, "Auto", "--", "0.0%", "--", "--:--", task.status))
        self.tree.selection_set(tid)
        task.start()

    def add_download(self):
        url = self.url_var.get().strip()
        if not url.startswith(("http://", "https://")):
            messagebox.showwarning("Invalid URL", "Please enter a valid http:// or https:// URL.")
            return
        self.url_var.set("")
        self._create_task(url)

    def add_download_from_ext(self, url, referer="", is_stream=False, quality=None):
        self._create_task(url, referer=referer, force_ytdlp=is_stream, quality=quality)
        self.root.deiconify()
        self.root.lift()

    def _start_local_server(self):
        app = self

        class Handler(BaseHTTPRequestHandler):
            def do_OPTIONS(self):
                self.send_response(200)
                self.send_header("Access-Control-Allow-Origin", "*")
                self.send_header("Access-Control-Allow-Methods", "POST, OPTIONS")
                self.send_header("Access-Control-Allow-Headers", "Content-Type")
                self.end_headers()

            def do_POST(self):
                if self.path == "/download":
                    length = int(self.headers.get("Content-Length", 0))
                    body = self.rfile.read(length)
                    try:
                        data = json.loads(body.decode("utf-8"))
                        url = data.get("url", "").strip()
                        referer = data.get("referer", "").strip()
                        is_stream = bool(data.get("is_stream", False))
                        quality = data.get("quality", "").strip() or None
                        if url.startswith(("http://", "https://")):
                            app.root.after(
                                0,
                                lambda u=url, r=referer, s=is_stream, q=quality: app.add_download_from_ext(u, r, s, q),
                            )
                            self.send_response(200)
                            self.send_header("Content-Type", "application/json")
                            self.send_header("Access-Control-Allow-Origin", "*")
                            self.end_headers()
                            self.wfile.write(b'{"status":"ok"}')
                            return
                    except Exception:
                        pass
                self.send_response(400)
                self.end_headers()

            def log_message(self, format, *args):
                pass

        def serve():
            try:
                HTTPServer(("127.0.0.1", 9876), Handler).serve_forever()
            except OSError:
                pass

        threading.Thread(target=serve, daemon=True).start()

    def _get_selected_task(self):
        sel = self.tree.selection()
        return self.tasks.get(sel[0]) if sel else None

    def pause_selected(self):
        task = self._get_selected_task()
        if task:
            task.pause()

    def resume_selected(self):
        task = self._get_selected_task()
        if task:
            task.resume()

    def remove_selected(self):
        task = self._get_selected_task()
        if not task:
            return
        if task.status not in ("Completed", "Cancelled", "Error"):
            task.cancel()
        self.tree.delete(task.task_id)
        del self.tasks[task.task_id]

    def open_folder(self):
        task = self._get_selected_task()
        target_dir = task.save_dir if task else self.dir_var.get()
        system = platform.system()
        try:
            if system == "Windows":
                if task and task.status == "Completed" and task.filepath and os.path.exists(task.filepath):
                    subprocess.Popen(["explorer", "/select,", os.path.normpath(task.filepath)])
                else:
                    os.startfile(target_dir)
            elif system == "Darwin":
                subprocess.Popen(["open", target_dir])
            else:
                subprocess.Popen(["xdg-open", target_dir])
        except Exception as e:
            messagebox.showerror("Error", f"Could not open folder:\n{e}")

    def _poll_ui(self):
        selected_id = self.tree.selection()[0] if self.tree.selection() else None

        for tid, task in list(self.tasks.items()):
            task.update_speed_and_eta()
            downloaded = task.get_downloaded()
            pct = task.get_progress_percent()

            engine_label = "Video" if task.engine == "ytdlp" else ("Direct" if task.engine == "http" else "Auto")
            size_str = f"{format_bytes(downloaded)} / {format_bytes(task.total_size)}" if task.total_size > 0 else format_bytes(downloaded)
            speed_str = f"{format_bytes(task.speed)}/s" if (task.status == "Downloading" and task.speed > 0) else "--"
            eta_str = format_eta(task.eta) if task.status == "Downloading" else "--:--"
            status_str = f"Error: {task.error_msg[:24]}" if task.status == "Error" else task.status

            self.tree.item(
                tid,
                values=(task.filename, engine_label, size_str, f"{pct:.1f}%", speed_str, eta_str, status_str),
            )

            if tid == selected_id:
                self.progress_bar["value"] = pct
                detail = f"Error: {task.error_msg}" if (task.status == "Error" and task.error_msg) else f"{task.filename} — {pct:.1f}% ({status_str})"
                self.selected_label.config(text=detail)

        if not selected_id:
            self.progress_bar["value"] = 0

        self.root.after(250, self._poll_ui)


if __name__ == "__main__":
    root = tk.Tk()
    app = DownloadManagerApp(root)
    root.mainloop()