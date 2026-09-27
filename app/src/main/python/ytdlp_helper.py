import json
import re
import os
import sys
import shutil
import importlib
import io
import subprocess
import tempfile
from types import SimpleNamespace
from urllib.parse import urlparse
import yt_dlp

DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
MOBILE_USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

# --- Logging helper ----------------------------------------------------
_DIAG_TAG = "PandorasBox/YtdlpHelper"
try:
    from android.util import Log as _ALog

    def _diag(message):
        try:
            _ALog.d(_DIAG_TAG, str(message))
        except Exception:
            print(f"{_DIAG_TAG}: {message}")
except Exception:
    def _diag(message):
        print(f"{_DIAG_TAG}: {message}")
# -------------------------------------------------------------------------

# Hosts that require the Android-specific yt-dlp compatibility patches below.
# Only Pornhub needs this today; other extractors (XHamster, XVideos, ...)
# work through stock yt-dlp and should not be routed through these patches.
ANDROID_SPECIAL_HOSTS = {"pornhub.com", "www.pornhub.com"}


def _enable_android_pornhub_curl_workaround():
    """Use Android's system curl for a page Python's TLS client is refused for."""
    curl_path = "/system/bin/curl"
    curl_exists = os.path.isfile(curl_path)
    curl_executable = curl_exists and os.access(curl_path, os.X_OK)
    _diag(f"_enable_android_pornhub_curl_workaround: curl_exists={curl_exists} curl_executable={curl_executable}")
    if not curl_exists:
        _diag("_enable_android_pornhub_curl_workaround: aborting patch install, /system/bin/curl not found")
        return

    from yt_dlp.extractor.pornhub import PornHubBaseIE
    if getattr(PornHubBaseIE, "_android_curl_page_enabled", False):
        _diag("_enable_android_pornhub_curl_workaround: patch already installed, skipping re-install")
        return

    original = PornHubBaseIE._download_webpage_handle
    original_request = PornHubBaseIE._request_webpage

    def curl_fetch(page_url, is_video_page):
        curl_args = [curl_path, "--location", "--fail", "--silent", "--show-error",
                     "--max-time", "30", "--retry", "2"]
        if is_video_page:
            curl_args.extend(["--cookie",
                              "age_verified=1; accessAgeDisclaimerPH=1; accessAgeDisclaimerUK=1; accessPH=1; platform=pc"])
        else:
            curl_args.extend(["--header", "Origin: https://www.pornhub.com",
                              "--header", "Referer: https://www.pornhub.com/"])
        response = subprocess.run(
            [*curl_args, page_url], capture_output=True, timeout=100, check=True)
        return response.stdout

    def download_page(self, url_or_request, *args, **kwargs):
        page_url = getattr(url_or_request, "url", url_or_request)
        parsed = urlparse(page_url) if isinstance(page_url, str) else None
        is_video_page = parsed and parsed.hostname in ANDROID_SPECIAL_HOSTS and parsed.path == "/view_video.php"
        _diag(f"download_page: host={getattr(parsed, 'hostname', None)} path={getattr(parsed, 'path', None)} is_video_page={bool(is_video_page)}")
        if is_video_page:
            try:
                webpage = curl_fetch(page_url, True).decode("utf-8", errors="replace")
                if "flashvars_" in webpage:
                    _diag("download_page: curl_fetch succeeded and page looked valid (flashvars_ present)")
                    return webpage, SimpleNamespace(url=page_url)
                _diag("download_page: curl_fetch returned a page WITHOUT flashvars_, falling back to original handler")
            except (OSError, subprocess.SubprocessError) as e:
                _diag(f"download_page: curl_fetch raised {type(e).__name__}: {e}, falling back to original handler")
        return original(self, url_or_request, *args, **kwargs)

    def request_playlist(self, url_or_request, *args, **kwargs):
        page_url = getattr(url_or_request, "url", url_or_request)
        parsed = urlparse(page_url) if isinstance(page_url, str) else None
        is_m3u8_request = bool(parsed and (parsed.hostname or "").endswith(".phncdn.com") and parsed.path.endswith(".m3u8"))
        if is_m3u8_request:
            _diag(f"request_playlist: m3u8 request detected host={parsed.hostname} path={parsed.path}")
            try:
                content = curl_fetch(page_url, False)
                if content.startswith(b"#EXTM3U"):
                    _diag("request_playlist: curl_fetch returned a valid #EXTM3U playlist")
                    response = io.BytesIO(content)
                    response.url = page_url
                    response.headers = {"Content-Type": "application/vnd.apple.mpegurl"}
                    return response
                _diag("request_playlist: curl_fetch returned content that is NOT #EXTM3U, falling back to original request path")
            except (OSError, subprocess.SubprocessError) as e:
                _diag(f"request_playlist: curl_fetch raised {type(e).__name__}: {e}, falling back to original request path")
        return original_request(self, url_or_request, *args, **kwargs)

    PornHubBaseIE._download_webpage_handle = download_page
    PornHubBaseIE._request_webpage = request_playlist
    PornHubBaseIE._android_curl_page_enabled = True
    _diag("_enable_android_pornhub_curl_workaround: patch installed on PornHubBaseIE")


def _enable_android_pornhub_urllib_workaround():
    """Use Android Python urllib for Pornhub webpage requests.

    The bundled yt-dlp Pornhub extractor currently receives HTTP 410 through
    its normal request path, while urllib can fetch the same page
    successfully with HTTP 200.
    """
    try:
        from yt_dlp.extractor.pornhub import PornHubBaseIE
        import urllib.request
        import urllib.error

        original_download_webpage_handle = PornHubBaseIE._download_webpage_handle

        if getattr(PornHubBaseIE, "_pandoras_box_urllib_patch", False):
            _diag("_enable_android_pornhub_urllib_workaround: already patched")
            return

        def android_download_webpage_handle(
            self,
            url,
            video_id,
            note="Downloading webpage",
            errnote="Unable to download webpage",
            fatal=True,
            encoding=None,
            data=None,
            headers=None,
            query=None,
            expected_status=None,
            impersonate=None,
            require_title=False,
            **kwargs
        ):
            host = (urlparse(url).hostname or "").lower()
            if host not in ANDROID_SPECIAL_HOSTS:
                return original_download_webpage_handle(
                    self,
                    url,
                    video_id,
                    note=note,
                    errnote=errnote,
                    fatal=fatal,
                    encoding=encoding,
                    data=data,
                    headers=headers,
                    query=query,
                    expected_status=expected_status,
                    impersonate=impersonate,
                    require_title=require_title,
                    **kwargs
                )

            request_headers = {
                "User-Agent": DEFAULT_USER_AGENT,
                "Accept": (
                    "text/html,application/xhtml+xml,application/xml;"
                    "q=0.9,image/avif,image/webp,*/*;q=0.8"
                ),
                "Accept-Language": "en-US,en;q=0.9",
                "Referer": "https://www.pornhub.com/",
                "Upgrade-Insecure-Requests": "1",
            }
            if headers:
                request_headers.update(headers)

            request = urllib.request.Request(
                url,
                data=data,
                headers=request_headers,
                method="POST" if data else "GET",
            )

            _diag(
                f"pornhub_urllib: fetching host={host} "
                f"path={urlparse(url).path} "
                f"impersonate={impersonate}"
            )

            try:
                response = urllib.request.urlopen(request, timeout=20)
                webpage_bytes = response.read()
                if urlparse(url).path == "/video/get_media":
                    _diag(
                        f"pornhub_urllib: get_media_body="
                        f"{webpage_bytes[:500]!r}"
                    )
                try:
                    webpage = webpage_bytes.decode(
                        encoding or "utf-8", errors="replace",
                    )
                except LookupError:
                    webpage = webpage_bytes.decode(
                        "utf-8", errors="replace",
                    )
                _diag(
                    f"pornhub_urllib: SUCCESS status="
                    f"{getattr(response, 'status', None)} "
                    f"final_url={response.geturl()} "
                    f"bytes={len(webpage_bytes)}"
                )
                return webpage, response
            except urllib.error.HTTPError as e:
                _diag(
                    f"pornhub_urllib: HTTPError status={e.code} "
                    f"reason={e.reason} final_url={e.geturl()}"
                )
                if fatal:
                    raise
                return False, e
            except Exception as e:
                _diag(
                    f"pornhub_urllib: EXCEPTION "
                    f"type={type(e).__name__} error={e}"
                )
                if fatal:
                    raise
                return False, None

        PornHubBaseIE._download_webpage_handle = android_download_webpage_handle
        PornHubBaseIE._pandoras_box_urllib_patch = True
        _diag(
            "_enable_android_pornhub_urllib_workaround: "
            "PornHubBaseIE._download_webpage_handle patched"
        )
    except Exception as e:
        _diag(
            f"_enable_android_pornhub_urllib_workaround: "
            f"SETUP_EXCEPTION type={type(e).__name__} error={e}"
        )


def enable_android_extractor_compatibility():
    """Install only the compatibility patches actually required by specific
    extractors on Android.

    Today that's Pornhub: yt-dlp's normal HTTP request path returns HTTP 410
    for Pornhub's webpage on Android, while curl / urllib fetch it
    successfully. XHamster and XVideos need no patching and are intentionally
    left on stock yt-dlp behavior.
    """
    _enable_android_pornhub_curl_workaround()
    _enable_android_pornhub_urllib_workaround()


enable_android_extractor_compatibility()


def unshorten_url(url):
    if not url:
        return url
    url_lower = url.lower()
    if 'tiktok.com' in url_lower or 'youtu.be' in url_lower or 't.co' in url_lower:
        try:
            import urllib.request
            req = urllib.request.Request(
                url,
                headers={
                    'User-Agent': DEFAULT_USER_AGENT,
                    'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
                }
            )
            with urllib.request.urlopen(req, timeout=8) as response:
                final_url = response.geturl()
                if final_url:
                    return final_url
        except Exception:
            pass
    return url

def describe_error(text):
    friendly = extract_friendly_error(text)
    lines = [x.strip() for x in (text or "").strip().splitlines() if x.strip()]
    raw_last = re.sub(r"\x1b\[[0-9;]*m", "", lines[-1])[:220] if lines else ""
    if raw_last and raw_last not in friendly:
        return f"{friendly} [{raw_last}]"
    return friendly

def extract_friendly_error(text):
    text = (text or "").strip()
    if not text:
        return "Download failed."

    patterns = [
        (r"Unsupported URL", "Unsupported URL or site."),
        (r"Private video", "This video is private."),
        (r"Sign in to confirm", "This site requires you to sign in."),
        (r"Sign in", "This video may require login."),
        (r"Video unavailable", "Video unavailable."),
        (r"HTTP Error 404", "Video or stream was not found."),
        (r"HTTP Error 410", "This stream link is no longer valid. Paste the video page URL for a fresh stream."),
        (r"HTTP Error 403", "Access denied by the site."),
        (r"Requested format is not available", "The selected quality is not available."),
        (r"No video formats found", "No downloadable video formats were found."),
        (r"Unable to extract", "The site changed or the URL could not be extracted."),
    ]
    for pattern, message in patterns:
        if re.search(pattern, text, re.I):
            return message

    lines = [x.strip() for x in text.splitlines() if x.strip()]
    useful = [x for x in lines if "ERROR:" in x or "WARNING:" not in x]
    if useful:
        last = useful[-1]
        last = re.sub(r"^\s*\[.*?\]\s*", "", last)
        return last[:260]
    return lines[-1][:260] if lines else "Download failed."

def extract_tiktok_native(url):
    import urllib.request

    url = unshorten_url(url)
    headers = {
        'User-Agent': DEFAULT_USER_AGENT,
        'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8',
        'Accept-Language': 'en-US,en;q=0.9',
        'Referer': url,
    }

    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=12) as resp:
            html = resp.read().decode('utf-8', errors='ignore')

            title = "TikTok Video"
            title_match = re.search(r'<title>(.*?)</title>', html)
            if title_match:
                title = title_match.group(1).replace(" | TikTok", "").strip()

            play_urls = []
            scripts = re.findall(r'<script[^>]*type="application/json"[^>]*>(.*?)</script>', html, re.DOTALL)
            for s in scripts:
                if 'playAddr' in s or 'playUrl' in s or 'downloadAddr' in s or 'tiktokcdn' in s:
                    try:
                        s_clean = s.encode('utf-8').decode('unicode_escape', errors='ignore')
                    except Exception:
                        s_clean = s
                    urls = re.findall(r'https?://[^\s"\'\\]+tiktokcdn[^\s"\'\\]+', s_clean)
                    for u in urls:
                        u_clean = u.replace('\\u0026', '&').replace('\\/', '/').replace('&amp;', '&')
                        if 'video' in u_clean or 'play' in u_clean or '.mp4' in u_clean or 'v16' in u_clean or 'v27' in u_clean or 'v39' in u_clean:
                            play_urls.append(u_clean)

            if not play_urls:
                raw_matches = re.findall(r'https?:\\?/\\?/[^\s"\'\\]+tiktokcdn[^\s"\'\\]+', html)
                for u in raw_matches:
                    u_clean = u.replace('\\u0026', '&').replace('\\/', '/').replace('&amp;', '&')
                    play_urls.append(u_clean)

            if play_urls:
                best_url = play_urls[0]
                return {
                    "is_playlist": False,
                    "title": title,
                    "uploader": "TikTok",
                    "duration": "",
                    "thumbnail": "",
                    "direct_url": best_url,
                    "video_formats": [{
                        "format_id": "direct",
                        "height": 1080,
                        "ext": "mp4",
                        "fps": 30,
                        "filesize": 0,
                        "tbr": 0,
                        "label": "1080p · Best quality"
                    }],
                    "audio_formats": [],
                    "is_m3u8": False
                }
    except Exception:
        pass
    return None

def download_direct_file(direct_url, output_path, referer=None, user_agent=None, progress_callback=None):
    import urllib.request

    # Real-time internal signal: fires once, here, at the true moment the direct-file fallback
    # path starts -- this function has exactly one caller (the TikTok native fallback branch in
    # download_video()), so reaching this line IS the "actual direct-file execution path"
    # Kotlin needs to know about, no guessing involved. This reuses the existing 9-argument
    # progress_callback signature rather than adding a 10th argument: the "status" slot is set
    # to a value ("direct_file_signal") that never appears in a real progress update (those are
    # only "downloading" / "completed" / "failed"), so Kotlin can recognize and swallow this one
    # call internally instead of forwarding it as a status/stage change. The "stage" slot is left
    # as the normal user-facing label so nothing user-visible depends on this being intercepted.
    if progress_callback:
        progress_callback("direct_file_signal", 0.0, "—", "—", "—", "—", "", "Downloading video", 0.0)

    headers = {
        'User-Agent': user_agent or DEFAULT_USER_AGENT,
        'Referer': referer or 'https://www.tiktok.com/',
        'Accept': '*/*',
    }

    try:
        req = urllib.request.Request(direct_url, headers=headers)
        with urllib.request.urlopen(req, timeout=30) as response:
            total_size = int(response.headers.get('Content-Length', 0))
            downloaded = 0
            chunk_size = 1024 * 64

            with open(output_path, 'wb') as f:
                while True:
                    chunk = response.read(chunk_size)
                    if not chunk:
                        break
                    f.write(chunk)
                    downloaded += len(chunk)
                    if progress_callback and total_size > 0:
                        percent = (downloaded / total_size) * 100.0
                        progress_callback(
                            "downloading",
                            float(percent),
                            format_bytes(downloaded),
                            format_bytes(total_size),
                            "—",
                            "—",
                            "",
                            "Downloading video",
                            float(total_size)
                        )

        if progress_callback:
            progress_callback("completed", 100.0, "—", "—", "—", "—", "", "Completed", 0.0)
        # Optional, secondary to the real-time signal above: lets anything that only looks at
        # the final JSON result (e.g. logs, future debugging) also see that this path was used.
        return json.dumps({"status": "completed", "file_path": output_path, "used_direct_file": True})
    except Exception as e:
        err_msg = str(e)
        if progress_callback:
            progress_callback("failed", 0.0, "—", "—", "—", "—", err_msg, "Failed", 0.0)
        return json.dumps({"status": "failed", "error": err_msg, "used_direct_file": True})

def load_custom_path(target_dir):
    if target_dir and os.path.exists(target_dir):
        if target_dir not in sys.path:
            sys.path.insert(0, target_dir)
        try:
            import yt_dlp
            importlib.reload(yt_dlp)
        except Exception:
            pass

def update_ytdlp(target_dir):
    if not target_dir:
        return json.dumps({"status": "failed", "error": "Invalid target path"})

    try:
        os.makedirs(target_dir, exist_ok=True)
        import subprocess
        cmd = [sys.executable, "-m", "pip", "install", "--upgrade", "yt-dlp", "--target", target_dir]
        res = subprocess.run(cmd, capture_output=True, text=True)
        if res.returncode == 0:
            if target_dir not in sys.path:
                sys.path.insert(0, target_dir)
            import yt_dlp
            importlib.reload(yt_dlp)
            version = getattr(yt_dlp, "__version__", "Updated")
            return json.dumps({"status": "success", "message": f"Updated yt-dlp to {version}"})
        else:
            return json.dumps({"status": "failed", "error": res.stderr or res.stdout or "Update command failed"})
    except Exception as e:
        return json.dumps({"status": "failed", "error": str(e)})

def format_bytes(size):
    if size is None or size <= 0:
        return "—"
    for unit in ['B', 'KB', 'MB', 'GB', 'TB']:
        if size < 1024.0 or unit == 'TB':
            return f"{size:.1f}{unit}" if unit != 'B' else f"{int(size)}B"
        size /= 1024.0
    return "—"

def sanitize_filename(name):
    if not name:
        return "video"
    name = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "", name)
    name = name.strip().rstrip(".")
    return name[:180] if name else "video"


def _create_pornhub_cookiefile():
    """Create a temporary Netscape cookie file for Pornhub's age/access cookies."""
    cookie_file = os.path.join(
        tempfile.gettempdir(),
        "pandoras_box_pornhub_cookies.txt",
    )

    cookie_data = """# Netscape HTTP Cookie File
.pornhub.com	TRUE	/	FALSE	0	age_verified	1
.pornhub.com	TRUE	/	FALSE	0	accessAgeDisclaimerPH	1
.pornhub.com	TRUE	/	FALSE	0	accessAgeDisclaimerUK	1
.pornhub.com	TRUE	/	FALSE	0	accessPH	1
.pornhub.com	TRUE	/	FALSE	0	platform	pc
"""

    try:
        with open(cookie_file, "w", encoding="utf-8") as f:
            f.write(cookie_data)
        _diag(f"_create_pornhub_cookiefile: created {cookie_file}")
        return cookie_file
    except OSError as e:
        _diag(f"_create_pornhub_cookiefile: failed: {type(e).__name__}: {e}")
        return None


def get_base_opts(url, referer=None, user_agent=None, ffmpeg_path=None, use_mobile_ua=False):
    url_lower = url.lower() if url else ""

    if not referer or not referer.strip():
        if 'phncdn.com' in url_lower:
            referer = "https://www.pornhub.com/"

    headers = {}
    if user_agent and user_agent.strip():
        headers['User-Agent'] = user_agent.strip()
    elif use_mobile_ua:
        headers['User-Agent'] = MOBILE_USER_AGENT
    if referer:
        headers['Referer'] = referer
    opts = {
        'quiet': True,
        'no_warnings': True,
        'age_limit': 18,
        'retries': 10,
        'fragment_retries': 10,
        'skip_unavailable_fragments': True,
        'socket_timeout': 30,
        'check_formats': None,
        'format_sort': ['vcodec:h264', 'acodec:aac', 'res', 'fps', 'size'],
    }

    if headers:
        opts['http_headers'] = headers

    if (urlparse(url).hostname or "").lower() in ANDROID_SPECIAL_HOSTS:
        cookie_file = _create_pornhub_cookiefile()
        if cookie_file:
            opts['cookiefile'] = cookie_file
            _diag("get_base_opts: Pornhub cookiefile configured")

    if os.path.isfile("/system/bin/curl") and (urlparse(url).hostname or "").lower() in ANDROID_SPECIAL_HOSTS:
        opts['external_downloader'] = {'http': '/system/bin/curl', 'https': '/system/bin/curl'}
        _diag("get_base_opts: external_downloader (curl) configured for pornhub.com media requests")

    if ffmpeg_path and os.path.exists(ffmpeg_path):
        opts['ffmpeg_location'] = ffmpeg_path

    return opts

LANGUAGE_NAMES = {
    "en": "English", "es": "Spanish", "fr": "French", "de": "German", "it": "Italian",
    "pt": "Portuguese", "ru": "Russian", "ja": "Japanese", "ko": "Korean", "zh": "Chinese",
    "hi": "Hindi", "ar": "Arabic", "tr": "Turkish", "nl": "Dutch", "pl": "Polish",
    "id": "Indonesian", "vi": "Vietnamese", "th": "Thai", "sv": "Swedish", "uk": "Ukrainian",
}

# YouTube appends a quality tier and variant tags to an audio track's format_note, e.g.
# "Arabic, medium" or "Arabic, low, DRC". Those describe one rendition of the track, not the
# track itself, so they are stripped before the note is shown in the Audio Track spinner.
_NOTE_VARIANT_SUFFIX = re.compile(r",\s*(ultralow|low|medium|high|default|drc)\b.*$", re.I)

def _clean_track_note(format_note):
    return _NOTE_VARIANT_SUFFIX.sub("", format_note or "").strip()

def _audio_track_name(language, format_note, is_original):
    """Best-effort human-readable name for an audio track (e.g. 'Original', 'Original (English)', 'Arabic')."""
    lang_name = None
    if language:
        lang_code = language.split("-")[0].lower()
        lang_name = LANGUAGE_NAMES.get(lang_code, language.upper())

    if is_original:
        return f"Original ({lang_name})" if lang_name else "Original"
    note = _clean_track_note(format_note)
    if note and note.lower() != "default":
        return note
    if lang_name:
        return lang_name
    return "Audio"

def parse_formats(info):
    videos = {}
    audio_tracks = {}

    for f in (info.get("formats") or []):
        vcodec = f.get("vcodec") or "none"
        acodec = f.get("acodec") or "none"
        height = f.get("height")

        if not height and f.get("resolution"):
            res_match = re.search(r'(\d{3,4})p?', str(f.get("resolution")))
            if res_match:
                try:
                    height = int(res_match.group(1))
                except Exception:
                    pass

        if not height and f.get("format_note"):
            res_match = re.search(r'(\d{3,4})p?', str(f.get("format_note")))
            if res_match:
                try:
                    height = int(res_match.group(1))
                except Exception:
                    pass

        # Some sites provide ready-to-play MP4 formats without codec metadata.
        # A real height still makes these selectable video formats.
        if vcodec != "none" or (height and f.get("vcodec") is None):
            h_val = int(height) if height else 0
            if h_val > 0:
                entry = {
                    "format_id": str(f.get("format_id", "")),
                    "height": h_val,
                    "ext": f.get("ext", ""),
                    "fps": f.get("fps") or 0,
                    "filesize": f.get("filesize") or f.get("filesize_approx") or 0,
                    "tbr": f.get("tbr") or 0,
                }
                old = videos.get(h_val)
                if old is None or entry["tbr"] > old["tbr"]:
                    videos[h_val] = entry
        elif acodec != "none" and vcodec == "none":
            if str(f.get("format_id", "")).endswith("-drc"):
                continue
            language = (f.get("language") or "").strip()
            format_note = (f.get("format_note") or "").strip()
            audio_track = f.get("audio_track") or {}
            is_original = bool(audio_track.get("audio_is_default")) or bool(re.search(r'\boriginal\b', format_note, re.I))
            abr = f.get("abr") or 0

            # YouTube (and other sites) publish several bitrate/codec renditions of the
            # SAME audio track (e.g. a 49/70/129 kbps opus ladder for the original audio,
            # another ladder for an Arabic dub, etc). Those are quality variants of one
            # track, not separate tracks, so they are grouped under one key here and only
            # the best-quality rendition is kept as that track's representative entry.
            track_id = audio_track.get("id")
            track_key = track_id if track_id else (language, is_original)

            entry = {
                "format_id": str(f.get("format_id", "")),
                "ext": f.get("ext", ""),
                "abr": abr,
                "filesize": f.get("filesize") or f.get("filesize_approx") or 0,
                "language": language,
                "format_note": format_note,
                "is_original": is_original,
            }
            old = audio_tracks.get(track_key)
            if old is None or abr > old["abr"]:
                audio_tracks[track_key] = entry

    video_list = sorted(videos.values(), key=lambda x: x["height"], reverse=True)
    # Original track first, then by language name, then by bitrate (so same-language
    # tracks group together instead of being scattered by bitrate alone).
    audio_list = sorted(
        audio_tracks.values(),
        key=lambda x: (not x["is_original"], _audio_track_name(x["language"], x["format_note"], x["is_original"]), -x["abr"])
    )

    for f in audio_list:
        name = _audio_track_name(f["language"], f["format_note"], f["is_original"])
        if f["abr"]:
            f["label"] = f"{name} • {f['abr']:.0f} kbps"
        else:
            f["label"] = name

    for f in video_list:
        if "label" not in f:
            fps = f["fps"]
            label = f"{f['height']}p"
            if fps and fps > 30:
                label += f" {fps:.0f}fps"
            size = format_bytes(f["filesize"])
            if size != "—":
                label += f" · {size}"
            f["label"] = label

    return video_list, audio_list

def extract_info(url, referer=None, user_agent=None, ffmpeg_path=None):
    if not url or not url.strip():
        return json.dumps({"error": "Empty URL provided."})

    url = unshorten_url(url.strip())

    _parsed_for_diag = urlparse(url)
    _diag(f"extract_info: entry host={_parsed_for_diag.hostname} path={_parsed_for_diag.path}")

    opts = get_base_opts(url, referer, user_agent, ffmpeg_path, use_mobile_ua=False)
    opts['extract_flat'] = 'in_playlist'
    opts['skip_download'] = True

    info = None
    last_err = None

    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=False)
        _diag(f"extract_info: desktop-UA attempt succeeded, extractor={info.get('extractor_key') if info else None}")
    except Exception as e:
        last_err = str(e)
        _diag(f"extract_info: desktop-UA attempt raised {type(e).__name__}: {last_err}")

    if not info:
        try:
            m_opts = get_base_opts(url, referer, user_agent, ffmpeg_path, use_mobile_ua=True)
            m_opts['extract_flat'] = 'in_playlist'
            m_opts['skip_download'] = True
            with yt_dlp.YoutubeDL(m_opts) as ydl:
                info = ydl.extract_info(url, download=False)
            _diag(f"extract_info: mobile-UA retry succeeded, extractor={info.get('extractor_key') if info else None}")
        except Exception as e:
            last_err = str(e)
            _diag(f"extract_info: mobile-UA retry raised {type(e).__name__}: {last_err}")

    # Native TikTok fallback if yt-dlp returns error / status code 0
    if not info and 'tiktok.com' in url.lower():
        native_info = extract_tiktok_native(url)
        if native_info:
            return json.dumps(native_info)

    if not info:
        _diag(f"extract_info: giving up, both attempts failed, last_err={last_err}")
        return json.dumps({"error": describe_error(last_err)})

    is_playlist = info.get('_type') == 'playlist' or 'entries' in info

    if is_playlist:
        entries = []
        playlist_host = (urlparse(url).hostname or "").lower()
        is_youtube_playlist = playlist_host in ("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be")
        for entry in info.get('entries', []) or []:
            if not entry:
                continue
            entry_url = entry.get('webpage_url') or entry.get('url') or ""
            if not entry_url.startswith(("http://", "https://")):
                vid = entry.get('id') or entry_url
                entry_url = f"https://www.youtube.com/watch?v={vid}" if is_youtube_playlist and vid else ""
            if not entry_url:
                continue

            entries.append({
                "title": entry.get('title') or entry.get('id') or "Untitled",
                "url": entry_url,
                "thumbnail": entry.get('thumbnail') or (
                    f"https://i.ytimg.com/vi/{entry.get('id')}/hqdefault.jpg"
                    if is_youtube_playlist and entry.get('id') else ""
                ),
            })

        return json.dumps({
            "is_playlist": True,
            "playlist_title": sanitize_filename(info.get('title') or "Playlist"),
            "entries": entries
        })
    else:
        videos, audios = parse_formats(info)
        duration_sec = info.get('duration') or 0
        mins, secs = divmod(int(duration_sec), 60)
        hrs, mins = divmod(mins, 60)
        duration_str = f"{hrs}:{mins:02d}:{secs:02d}" if hrs else f"{mins}:{secs:02d}"

        thumbnail_url = info.get('thumbnail') or ""
        if info.get('thumbnails'):
            try:
                jpg_thumbs = [t for t in info.get('thumbnails') if t.get('url') and '.jpg' in t.get('url').lower()]
                if jpg_thumbs:
                    best_jpg = max(jpg_thumbs, key=lambda x: (x.get('width') or 0) * (x.get('height') or 0))
                    thumbnail_url = best_jpg.get('url') or thumbnail_url
                elif not thumbnail_url:
                    all_thumbs = [t for t in info.get('thumbnails') if t.get('url')]
                    if all_thumbs:
                        best_t = max(all_thumbs, key=lambda x: (x.get('width') or 0) * (x.get('height') or 0))
                        thumbnail_url = best_t.get('url') or thumbnail_url
            except Exception:
                pass

        vid = info.get('id')
        url_lower = url.lower()
        if vid and ('youtube.com' in url_lower or 'youtu.be' in url_lower):
            thumbnail_url = f"https://i.ytimg.com/vi/{vid}/hqdefault.jpg"

        return json.dumps({
            "is_playlist": False,
            "title": info.get('title') or "Untitled",
            "uploader": info.get('uploader') or info.get('channel') or "",
            "duration": duration_str if duration_sec > 0 else "",
            "thumbnail": thumbnail_url,
            "video_formats": videos,
            "audio_formats": audios,
            "is_m3u8": ".m3u8" in url.lower()
        })

# yt-dlp format ids are normally plain tokens such as "251" or "251-0". An id containing
# selector syntax ('/', '+', ',', brackets, spaces, ...) would change the meaning of the whole
# selector if it were pasted in, so such an id is ignored and the normal bestaudio fallback
# is used instead.
_SAFE_FORMAT_ID = re.compile(r"[A-Za-z0-9._-]+")

def _clean_audio_format_id(audio_format_id):
    candidate = (audio_format_id or "").strip()
    return candidate if _SAFE_FORMAT_ID.fullmatch(candidate) else None


def build_format_string(quality, container, has_ffmpeg=True, audio_format_id=None):
    """Builds a yt-dlp format selector.

    audio_format_id, when given, is the yt-dlp format_id of a specific audio track the
    user picked (e.g. a dubbed or original-language track). It is always tried first,
    then the selector falls back to bestaudio, then to an all-in-one 'best' stream.

    Each branch below is assembled as an explicit list of complete alternative
    selectors joined with '/'. audio_format_id is never spliced into the middle of an
    existing '/'-separated string; every alternative that should include it is written
    out in full. The id itself is also validated first (see _clean_audio_format_id), so an
    id that contains selector characters can never alter the structure of the string.
    """
    audio_format_id = _clean_audio_format_id(audio_format_id)

    if container == "mp3":
        alts = []
        if audio_format_id:
            alts.append(audio_format_id)
        alts.append("bestaudio")
        alts.append("best")
        return "/".join(alts)

    if container == "webm":
        if quality and quality.startswith("id:"):
            parts = quality.split(":")
            fid = parts[1] if len(parts) > 1 else ""
            if _SAFE_FORMAT_ID.fullmatch(fid):
                selected_audio = f"{fid}+{audio_format_id}/" if audio_format_id else ""
                return f"{selected_audio}{fid}+bestaudio[ext=webm]/{fid}+bestaudio/{fid}/bestvideo[ext=webm]+bestaudio[ext=webm]/best[ext=webm]/best"
        height_filter = ""
        if quality and quality.startswith("h:") and quality[2:].isdigit():
            height_filter = f"[height<={quality[2:]}]"
        fallback = f"best{height_filter}" if height_filter else "best"
        selected_audio = f"bestvideo[ext=webm]{height_filter}+{audio_format_id}/" if audio_format_id else ""
        return (
            f"{selected_audio}"
            f"bestvideo[ext=webm]{height_filter}+bestaudio[ext=webm]/"
            f"best[ext=webm]{height_filter}/"
            f"bestvideo{height_filter}+bestaudio/"
            f"{fallback}"
        )

    if quality and quality.startswith("id:"):
        parts = quality.split(":")
        fid = parts[1] if len(parts) > 1 else ""
        height = parts[2] if len(parts) > 2 else ""
        if _SAFE_FORMAT_ID.fullmatch(fid) and height.isdigit():
            alts = []
            if container == "mp4":
                if audio_format_id:
                    alts.append(f"{fid}+{audio_format_id}")
                alts.append(f"{fid}+bestaudio[ext=m4a]")
                alts.append(f"{fid}+bestaudio")
                if audio_format_id:
                    alts.append(f"bestvideo[height<={height}][ext=mp4]+{audio_format_id}")
                alts.append(f"bestvideo[height<={height}][ext=mp4]+bestaudio[ext=m4a]")
                alts.append(f"best[height<={height}]")
                alts.append("best")
            else:
                if audio_format_id:
                    alts.append(f"{fid}+{audio_format_id}")
                alts.append(f"{fid}+bestaudio")
                if audio_format_id:
                    alts.append(f"bestvideo[height<={height}]+{audio_format_id}")
                alts.append(f"bestvideo[height<={height}]+bestaudio")
                alts.append(f"best[height<={height}]")
                alts.append("best")
            return "/".join(alts)

    if quality and quality.startswith("h:") and quality[2:].isdigit():
        height = quality[2:]
        alts = []
        if container == "mp4":
            if audio_format_id:
                alts.append(f"bestvideo[height<={height}][ext=mp4][vcodec^=avc1]+{audio_format_id}")
                alts.append(f"bestvideo[height<={height}][ext=mp4]+{audio_format_id}")
            alts.append(f"bestvideo[height<={height}][ext=mp4][vcodec^=avc1]+bestaudio[ext=m4a]")
            alts.append(f"bestvideo[height<={height}][ext=mp4]+bestaudio[ext=m4a]")
            if audio_format_id:
                alts.append(f"bestvideo[height<={height}]+{audio_format_id}")
            alts.append(f"bestvideo[height<={height}]+bestaudio")
            alts.append(f"best[height<={height}][ext=mp4]")
            alts.append(f"best[height<={height}]")
            alts.append("best")
        else:
            if audio_format_id:
                alts.append(f"bestvideo[height<={height}]+{audio_format_id}")
            alts.append(f"bestvideo[height<={height}]+bestaudio")
            alts.append(f"best[height<={height}]")
            alts.append("best")
        return "/".join(alts)

    # Default / "best" quality: no codec or container filters, so the highest resolution
    # the site offers is chosen. The order of preference is set by BEST_FORMAT_SORT below.
    alts = []
    if audio_format_id:
        alts.append(f"bestvideo+{audio_format_id}")
    alts.append("bestvideo+bestaudio")
    alts.append("best")
    return "/".join(alts)


# Used ONLY for the automatic "Best" choice. Resolution and frame rate come first, and the
# codec only breaks ties: H.264 first, then HEVC / VP9, and AV1 last (hardest for phones to play).
BEST_FORMAT_SORT = [
    'hasvid', 'ie_pref', 'lang', 'quality',
    'res', 'fps', 'source',
    'vcodec:h264', 'channels', 'acodec:aac',
    'size', 'br', 'asr', 'proto', 'ext', 'hasaud',
]

# Used for audio-only downloads (MP3 extraction, or the automatic 'bestaudio' fallback
# when no specific audio track / format was chosen). Bitrate ranks ABOVE codec preference
# here — unlike BEST_FORMAT_SORT above — so a higher-bitrate rendition never loses out to
# a lower-bitrate one just because the lower one happens to be in a preferred codec
# (e.g. a 70 kbps AAC stream no longer beats a 129 kbps Opus stream of the same track).
AUDIO_ONLY_FORMAT_SORT = [
    'ie_pref', 'lang', 'abr', 'asr', 'acodec:aac', 'size', 'ext',
]


def is_auto_best(quality, container):
    q = (quality or "").strip()
    return not (q.startswith("id:") or q.startswith("h:"))

# ---------------------------------------------------------------------------
# Switches you can flip to undo a feature (change True/False, then rebuild the app)
# ---------------------------------------------------------------------------
# True  = subtitles are put INSIDE the video file (MP4 soft subtitles).
# False = subtitles are saved as separate .vtt files next to the video (the old behaviour).
EMBED_SUBTITLES_IN_VIDEO = True

# True  = the "Embed Thumbnail & Metadata" switch also puts the thumbnail inside the file as cover art.
# False = only the metadata (title, uploader, ...) is embedded (the old behaviour).
EMBED_THUMBNAIL = True


def is_postprocess_error(text):
    return "postprocessing" in (text or "").lower()


def configure_download_opts(o, format_type, quality, subtitles, embed_meta, has_ffmpeg, with_extras=True, audio_format_id=None):
    """Fills in format, merge, subtitle, thumbnail and metadata options for one download attempt.

    with_extras=False skips the embedding steps (used as a safe retry if embedding failed).
    audio_format_id, when given, is tried first for the audio track (see build_format_string)."""
    o['format'] = build_format_string(quality, format_type, has_ffmpeg=has_ffmpeg, audio_format_id=audio_format_id)
    if format_type == "mp3":
        o['format_sort'] = AUDIO_ONLY_FORMAT_SORT
    elif is_auto_best(quality, format_type):
        o['format_sort'] = BEST_FORMAT_SORT

    if format_type == "mp4":
        o['merge_output_format'] = 'mp4'
        o['recode_video'] = 'mp4'
        if has_ffmpeg:
            # Only the merge step gets these arguments, so thumbnail/subtitle steps are not affected.
            merge_args = ['-c:v', 'copy', '-c:a', 'aac', '-movflags', '+faststart']
            o['postprocessor_args'] = {'merger': merge_args, 'videoconvertor': merge_args}
    elif format_type == "webm":
        o['merge_output_format'] = 'webm'
        if has_ffmpeg:
            o['recode_video'] = 'webm'

    pps = []

    if format_type == "mp3" and has_ffmpeg:
        pps.append({
            'key': 'FFmpegExtractAudio',
            'preferredcodec': 'mp3',
            'preferredquality': '192',
        })

    # Subtitles: always downloaded when the switch is on; embedded into the video when possible.
    if subtitles and format_type != "mp3":
        o['writesubtitles'] = True
        o['writeautomaticsub'] = True
        o['subtitleslangs'] = ['en.*', 'en']
        if has_ffmpeg and with_extras and EMBED_SUBTITLES_IN_VIDEO:
            pps.insert(0, {'key': 'FFmpegSubtitlesConvertor', 'format': 'srt', 'when': 'before_dl'})
            pps.append({'key': 'FFmpegEmbedSubtitle', 'already_have_subtitle': False})

    # Metadata + thumbnail (cover art)
    if embed_meta and has_ffmpeg and with_extras:
        if EMBED_THUMBNAIL:
            o['writethumbnail'] = True
            pps.insert(0, {'key': 'FFmpegThumbnailsConvertor', 'format': 'jpg', 'when': 'before_dl'})
        pps.append({'key': 'FFmpegMetadata'})
        if EMBED_THUMBNAIL:
            pps.append({'key': 'EmbedThumbnail', 'already_have_thumbnail': False})

    if pps:
        o['postprocessors'] = pps


def cleanup_thumbnail_files(path):
    """Removes a leftover thumbnail image so it does not show up as a picture in the gallery."""
    try:
        if not path or '%(' in path:
            return
        base = os.path.splitext(path)[0]
        for ext in ('.jpg', '.jpeg', '.png', '.webp'):
            f = base + ext
            if os.path.exists(f):
                os.remove(f)
    except Exception:
        pass


def download_video(url, output_path, format_type="mp4", quality="best", subtitles=False, embed_meta=False, referer=None, user_agent=None, ffmpeg_path=None, audio_format_id=None, progress_callback=None):
    # yt-dlp downloads video and audio as two separate streams (then merges them) whenever the
    # chosen format needs it. Each stream's own progress hook only knows about ITS bytes, so a
    # naive "downloaded/total" percent restarts at 0 for the second stream -- which used to look
    # like the bar jumping around. We instead tag every update with which stage produced it
    # ("Downloading video" / "Downloading audio" / "Merging" / "Finishing"), each with its own
    # independent 0-100 percent. The UI keeps the highest percent seen PER STAGE, so switching
    # stages is a legitimate reset instead of a backwards jump.
    def stream_stage_label(d):
        info = d.get('info_dict') or {}
        vcodec = info.get('vcodec')
        acodec = info.get('acodec')
        has_video = vcodec not in (None, 'none')
        has_audio = acodec not in (None, 'none')
        if has_audio and not has_video:
            return "Downloading audio"
        return "Downloading video"

    def hook(d):
        if progress_callback is None:
            return
        status = d.get('status')
        stage_label = stream_stage_label(d)
        if status == 'downloading':
            downloaded = d.get('downloaded_bytes') or 0
            speed = d.get('speed') or 0
            eta = d.get('eta') or 0

            # Always derive the percent from bytes -- never from yt-dlp's own percent string --
            # but not every "total" is equally trustworthy:
            #   - total_bytes comes straight from the Content-Length header: exact.
            #   - total_bytes_estimate is a GUESS (used for HLS/DASH streams that don't expose a
            #     byte size up front) and yt-dlp is free to REVISE it as more fragments arrive.
            # We used to cap an estimate-based percent at 99% to hide the case where an early,
            # too-small estimate briefly claimed "done". That cap is exactly what caused the
            # opposite bug: once an early lowball estimate pushed percent up to the 99% ceiling,
            # the Kotlin side's "never go backwards" guard latched onto that 99% and kept showing
            # it even after yt-dlp revised the estimate upward and the true ratio (downloaded /
            # real total) was e.g. 8%. A percent frozen at a hard-coded cap forever is worse than
            # letting it briefly overshoot and self-correct.
            #
            # So there is no cap here beyond the natural 0-100 range, and we always use whichever
            # total is currently best (exact over estimate). We also report which raw total byte
            # count we're using (0 if none), so the Kotlin layer can tell a genuine total
            # *revision* apart from ordinary jitter and reset its backwards-guard accordingly
            # instead of freezing on a stale, now-wrong percent (requirement: total_bytes is
            # allowed to update; only true jitter within the SAME total should be smoothed).
            exact_total = d.get('total_bytes') or 0
            estimated_total = d.get('total_bytes_estimate') or 0
            total_for_percent = exact_total or estimated_total

            if total_for_percent > 0:
                total_for_display = total_for_percent
                percent = min((downloaded / total_for_percent) * 100.0, 100.0)
            else:
                # No total known yet at all -- report 0 rather than guessing, since we genuinely
                # don't know how far along we are.
                total_for_display = 0
                percent = 0.0
            percent = max(0.0, percent)

            speed_str = f"{format_bytes(speed)}/s" if speed else "—"
            eta_mins, eta_secs = divmod(int(eta), 60)
            eta_str = f"{eta_mins:02d}:{eta_secs:02d}" if eta else "—"

            progress_callback(
                "downloading",
                float(percent),
                format_bytes(downloaded),
                format_bytes(total_for_display) if total_for_display > 0 else "—",
                speed_str,
                eta_str,
                "",
                stage_label,
                float(total_for_display)
            )
        elif status == 'finished':
            # This one stream (video, or audio) is fully downloaded. Derive the percent from its
            # own bytes rather than blindly assuming 100 (requirement: never blindly trust a
            # percent when bytes are available) -- in practice downloaded should equal total here,
            # so this still ends up at/near 100%, but it is now a computed fact, not an assumption.
            # If another stream still needs downloading, its 'downloading' hook will carry a
            # different stage label, so restarting at 0% for it is expected, not a glitch.
            downloaded = d.get('downloaded_bytes') or 0
            total = d.get('total_bytes') or d.get('total_bytes_estimate') or downloaded
            percent = min((downloaded / total) * 100.0, 100.0) if total > 0 else 100.0
            progress_callback(
                "downloading",
                float(max(0.0, percent)),
                format_bytes(downloaded),
                format_bytes(total) if total > 0 else "—",
                "—",
                "—",
                "",
                stage_label,
                float(total)
            )

    def pp_hook(d):
        # Fires for postprocessing steps that run after both streams are downloaded: merging
        # video+audio, embedding subtitles/thumbnail/metadata, etc. yt-dlp does not expose a
        # fine-grained percent for these (they're usually near-instant stream copies), so we
        # report a started/finished pair per step -- effectively an indeterminate 0 -> 100.
        if progress_callback is None:
            return
        name = d.get('postprocessor', '')
        status = d.get('status')
        is_merge = name == 'Merger'
        stage_label = "Merging" if is_merge else "Finishing"
        if status == 'started':
            progress_callback("downloading", 0.0, "—", "—", "—", "—", "", stage_label, 0.0)
        elif status == 'finished':
            progress_callback("downloading", 100.0, "—", "—", "—", "—", "", stage_label, 0.0)

    url = unshorten_url(url.strip())

    has_ffmpeg = False
    if ffmpeg_path and os.path.exists(ffmpeg_path):
        has_ffmpeg = True
    elif shutil.which("ffmpeg") is not None or shutil.which("ffmpeg.exe") is not None:
        has_ffmpeg = True

    extras_wanted = has_ffmpeg and (embed_meta or (subtitles and EMBED_SUBTITLES_IN_VIDEO))

    def run_attempt(use_mobile_ua, with_extras):
        o = get_base_opts(url, referer, user_agent, ffmpeg_path, use_mobile_ua=use_mobile_ua)
        o['outtmpl'] = output_path
        o['progress_hooks'] = [hook]
        o['postprocessor_hooks'] = [pp_hook]
        configure_download_opts(o, format_type, quality, subtitles, embed_meta, has_ffmpeg, with_extras, audio_format_id=audio_format_id)

        final_path = output_path
        with yt_dlp.YoutubeDL(o) as ydl:
            info = ydl.extract_info(url, download=True)
            if info:
                try:
                    prepared = ydl.prepare_filename(info)
                    if prepared:
                        final_path = prepared
                except Exception:
                    pass
        return final_path

    def finish(path, note=""):
        if format_type == "mp3" and has_ffmpeg:
            base, _ = os.path.splitext(path)
            path = base + ".mp3"
        if progress_callback:
            progress_callback("completed", 100.0, "—", "—", "—", "—", note, "Completed", 0.0)
        return json.dumps({"status": "completed", "file_path": path, "warning": note})

    last_err = None

    # Attempt 1: normal download with subtitles / thumbnail / metadata embedding
    try:
        return finish(run_attempt(False, True))
    except Exception as e:
        last_err = str(e)

    # If only the embedding step failed, keep the video and skip the extras instead of starting over.
    if extras_wanted and is_postprocess_error(last_err):
        try:
            path = run_attempt(False, False)
            cleanup_thumbnail_files(path)
            return finish(path, "Saved, but embedding subtitles/thumbnail failed: " + describe_error(last_err))
        except Exception as e:
            last_err = str(e)

    # Attempt 2: same download pretending to be a phone browser
    try:
        return finish(run_attempt(True, True))
    except Exception as e:
        last_err = str(e)

    # Native direct download fallback for TikTok
    if 'tiktok.com' in url.lower():
        native_info = extract_tiktok_native(url)
        if native_info and native_info.get('direct_url'):
            safe_path = output_path.replace("%(title)s", sanitize_filename(native_info.get('title') or "TikTok video"))
            return download_direct_file(
                direct_url=native_info['direct_url'],
                output_path=safe_path,
                referer=url,
                user_agent=user_agent or DEFAULT_USER_AGENT,
                progress_callback=progress_callback
            )

    err_msg = describe_error(last_err)
    if progress_callback:
        progress_callback("failed", 0.0, "—", "—", "—", "—", err_msg, "Failed", 0.0)
    return json.dumps({"status": "failed", "error": err_msg})
