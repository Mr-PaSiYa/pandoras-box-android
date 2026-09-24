import json
import re
import os
import sys
import shutil
import importlib
import yt_dlp

DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
MOBILE_USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

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
                            ""
                        )

        if progress_callback:
            progress_callback("completed", 100.0, "—", "—", "—", "—", "")
        return json.dumps({"status": "completed", "file_path": output_path})
    except Exception as e:
        err_msg = str(e)
        if progress_callback:
            progress_callback("failed", 0.0, "—", "—", "—", "—", err_msg)
        return json.dumps({"status": "failed", "error": err_msg})

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

def get_base_opts(url, referer=None, user_agent=None, ffmpeg_path=None, use_mobile_ua=False):
    url_lower = url.lower() if url else ""

    cookie_str = None
    if not referer or not referer.strip():
        if 'tiktok.com' in url_lower:
            referer = url
        elif any(dom in url_lower for dom in ['pornhub', 'phncdn', 'phub']):
            referer = url if 'view_video.php' in url_lower else "https://www.pornhub.com/"
            cookie_str = "age_verified=1; bs=1; accessAgeDisclaimerPH=1; hl=en"
        elif 'xhamster' in url_lower:
            referer = "https://xhamster.com/"
            cookie_str = "age_verified=1;"
        elif 'xvideos' in url_lower:
            referer = "https://www.xvideos.com/"
        elif 'spankbang' in url_lower:
            referer = "https://spankbang.com/"
            cookie_str = "age_verified=1; sb_age=18;"
        elif 'redtube' in url_lower:
            referer = "https://www.redtube.com/"
            cookie_str = "age_verified=1; accessAgeDisclaimerRT=1;"
        elif 'youporn' in url_lower:
            referer = "https://www.youporn.com/"
            cookie_str = "age_verified=1;"
        elif 'instagram.com' in url_lower:
            referer = "https://www.instagram.com/"
        elif 'twitter.com' in url_lower or 'x.com' in url_lower:
            referer = "https://x.com/"
        elif 'youtube.com' in url_lower or 'youtu.be' in url_lower:
            referer = "https://www.youtube.com/"
        else:
            referer = url

    if user_agent and user_agent.strip():
        ua = user_agent.strip()
    elif use_mobile_ua:
        ua = MOBILE_USER_AGENT
    else:
        ua = DEFAULT_USER_AGENT

    headers = {
        'User-Agent': ua,
        'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8',
        'Accept-Language': 'en-US,en;q=0.9',
        'Sec-Ch-Ua': '"Chromium";v="128", "Not=A?Brand";v="24", "Google Chrome";v="128"',
        'Sec-Ch-Ua-Mobile': '?1' if use_mobile_ua else '?0',
        'Sec-Ch-Ua-Platform': '"Android"' if use_mobile_ua else '"Windows"',
        'Sec-Fetch-Dest': 'document',
        'Sec-Fetch-Mode': 'navigate',
        'Sec-Fetch-Site': 'cross-site',
        'Sec-Fetch-User': '?1',
        'Upgrade-Insecure-Requests': '1',
    }
    if referer:
        headers['Referer'] = referer
    if cookie_str:
        headers['Cookie'] = cookie_str

    opts = {
        'quiet': True,
        'no_warnings': True,
        'nocheckcertificate': True,
        'age_limit': 18,
        'geo_bypass': True,
        'geo_bypass_country': 'US',
        'http_headers': headers,
        'retries': 10,
        'fragment_retries': 10,
        'skip_unavailable_fragments': True,
        'legacy_server_connect': True,
        'socket_timeout': 30,
        'check_formats': None,
        'format_sort': ['vcodec:h264', 'acodec:aac', 'res', 'fps', 'size'],
        'extractor_args': {
            'tiktok': {
                'app_version': '30.0.0',
                'manifest_app_version': '30.0.0',
            },
            'pornhub': {
                'check_formats': ['hls', 'mp4'],
            }
        }
    }

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

        if vcodec != "none":
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

    if not video_list:
        video_list.append({
            "format_id": "best",
            "height": 720,
            "ext": info.get("ext") or "mp4",
            "fps": 0,
            "filesize": info.get("filesize") or info.get("filesize_approx") or 0,
            "tbr": 0,
            "label": "Best available"
        })

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

    opts = get_base_opts(url, referer, user_agent, ffmpeg_path, use_mobile_ua=False)
    opts['extract_flat'] = 'in_playlist'
    opts['skip_download'] = True

    info = None
    last_err = None

    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=False)
    except Exception as e:
        last_err = str(e)

    if not info:
        try:
            m_opts = get_base_opts(url, referer, user_agent, ffmpeg_path, use_mobile_ua=True)
            m_opts['extract_flat'] = 'in_playlist'
            m_opts['skip_download'] = True
            with yt_dlp.YoutubeDL(m_opts) as ydl:
                info = ydl.extract_info(url, download=False)
        except Exception as e:
            last_err = str(e)

    # Native TikTok fallback if yt-dlp returns error / status code 0
    if not info and 'tiktok.com' in url.lower():
        native_info = extract_tiktok_native(url)
        if native_info:
            return json.dumps(native_info)

    if not info:
        return json.dumps({"error": describe_error(last_err)})

    is_playlist = info.get('_type') == 'playlist' or 'entries' in info

    if is_playlist:
        entries = []
        for entry in info.get('entries', []) or []:
            if not entry:
                continue
            entry_url = entry.get('url') or entry.get('webpage_url') or ""
            if not entry_url.startswith("http"):
                vid = entry.get('id') or entry_url
                entry_url = f"https://www.youtube.com/watch?v={vid}" if vid else ""

            entries.append({
                "title": entry.get('title') or entry.get('id') or "Untitled",
                "url": entry_url,
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

    if quality and quality.startswith("id:"):
        parts = quality.split(":")
        fid = parts[1] if len(parts) > 1 else ""
        height = parts[2] if len(parts) > 2 else ""
        if fid:
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

    if quality and quality.startswith("h:"):
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
    def hook(d):
        if progress_callback is None:
            return
        status = d.get('status')
        if status == 'downloading':
            total = d.get('total_bytes') or d.get('total_bytes_estimate') or 0
            downloaded = d.get('downloaded_bytes') or 0
            speed = d.get('speed') or 0
            eta = d.get('eta') or 0
            percent = (downloaded / total * 100.0) if total > 0 else 0.0

            speed_str = f"{format_bytes(speed)}/s" if speed else "—"
            eta_mins, eta_secs = divmod(int(eta), 60)
            eta_str = f"{eta_mins:02d}:{eta_secs:02d}" if eta else "—"

            progress_callback(
                "downloading",
                float(percent),
                format_bytes(downloaded),
                format_bytes(total),
                speed_str,
                eta_str,
                ""
            )
        elif status == 'finished':
            progress_callback("processing", 99.0, "—", "—", "—", "—", "")

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
            progress_callback("completed", 100.0, "—", "—", "—", "—", note)
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
        progress_callback("failed", 0.0, "—", "—", "—", "—", err_msg)
    return json.dumps({"status": "failed", "error": err_msg})