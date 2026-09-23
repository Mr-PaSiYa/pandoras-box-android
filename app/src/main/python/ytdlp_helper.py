import json
import re
import os
import shutil
import yt_dlp

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

def parse_formats(info):
    videos = {}
    audios = []

    for f in info.get("formats", []):
        vcodec = f.get("vcodec") or "none"
        acodec = f.get("acodec") or "none"
        if vcodec != "none" and f.get("height"):
            height = int(f["height"])
            entry = {
                "format_id": str(f.get("format_id", "")),
                "height": height,
                "ext": f.get("ext", ""),
                "fps": f.get("fps") or 0,
                "filesize": f.get("filesize") or f.get("filesize_approx") or 0,
                "tbr": f.get("tbr") or 0,
            }
            old = videos.get(height)
            if old is None or entry["tbr"] > old["tbr"]:
                videos[height] = entry
        elif acodec != "none" and vcodec == "none":
            audios.append({
                "format_id": str(f.get("format_id", "")),
                "ext": f.get("ext", ""),
                "abr": f.get("abr") or 0,
                "filesize": f.get("filesize") or f.get("filesize_approx") or 0,
            })

    video_list = sorted(videos.values(), key=lambda x: x["height"], reverse=True)
    audio_list = sorted(audios, key=lambda x: x["abr"], reverse=True)

    for f in video_list:
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
    opts = {
        'extract_flat': 'in_playlist',
        'skip_download': True,
        'quiet': True,
        'no_warnings': True,
        'nocheckcertificate': True,
    }
    if ffmpeg_path and os.path.exists(ffmpeg_path):
        opts['ffmpeg_location'] = ffmpeg_path

    headers = {}
    if referer:
        headers['Referer'] = referer
    if user_agent:
        headers['User-Agent'] = user_agent
    if headers:
        opts['http_headers'] = headers

    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=False)
    except Exception as e:
        return json.dumps({"error": f"Failed to extract video details: {str(e)}"})

    if not info:
        return json.dumps({"error": "Unable to extract video details."})

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

        return json.dumps({
            "is_playlist": False,
            "title": info.get('title') or "Untitled",
            "uploader": info.get('uploader') or info.get('channel') or "",
            "duration": duration_str if duration_sec > 0 else "",
            "thumbnail": info.get('thumbnail') or "",
            "video_formats": videos,
            "audio_formats": audios,
            "is_m3u8": ".m3u8" in url.lower()
        })

def download_video(url, output_path, format_type="mp4", quality="best", subtitles=False, embed_meta=False, referer=None, user_agent=None, ffmpeg_path=None, progress_callback=None):
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

    has_ffmpeg = False
    opts = {
        'outtmpl': output_path,
        'progress_hooks': [hook],
        'quiet': True,
        'no_warnings': True,
        'nocheckcertificate': True,
    }

    if ffmpeg_path and os.path.exists(ffmpeg_path):
        opts['ffmpeg_location'] = ffmpeg_path
        has_ffmpeg = True
    elif shutil.which("ffmpeg") is not None or shutil.which("ffmpeg.exe") is not None:
        has_ffmpeg = True

    if not has_ffmpeg:
        if format_type == "mp3":
            fmt_str = "bestaudio/best"
        elif quality and quality.startswith("h:"):
            height = quality[2:]
            fmt_str = f"best[height<={height}]/best"
        else:
            fmt_str = "best"
    else:
        if format_type == "mp3":
            fmt_str = "bestaudio/best"
        elif quality and quality.startswith("h:"):
            height = quality[2:]
            if format_type == "mp4":
                fmt_str = f"bestvideo[height<={height}][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height<={height}]+bestaudio/best[height<={height}]/best"
            else:
                fmt_str = f"bestvideo[height<={height}]+bestaudio/best[height<={height}]/best"
        else:
            fmt_str = "bestvideo+bestaudio/best"

    opts['format'] = fmt_str

    if has_ffmpeg and format_type == "mp3":
        opts['postprocessors'] = [{
            'key': 'FFmpegExtractAudio',
            'preferredcodec': 'mp3',
            'preferredquality': '192',
        }]

    if subtitles:
        opts['writesubtitles'] = True
        opts['writeautomaticsub'] = True
        opts['subtitleslangs'] = ['en.*', 'en']

    if has_ffmpeg and embed_meta:
        if 'postprocessors' not in opts:
            opts['postprocessors'] = []
        opts['postprocessors'].append({'key': 'FFmpegMetadata'})

    headers = {}
    if referer:
        headers['Referer'] = referer
    if user_agent:
        headers['User-Agent'] = user_agent
    if headers:
        opts['http_headers'] = headers

    try:
        final_file_path = output_path
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=True)
            if info:
                try:
                    prepared = ydl.prepare_filename(info)
                    if prepared:
                        final_file_path = prepared
                except Exception:
                    pass

        if format_type == "mp3" and has_ffmpeg:
            base, _ = os.path.splitext(final_file_path)
            final_file_path = base + ".mp3"

        if progress_callback:
            progress_callback("completed", 100.0, "—", "—", "—", "—", "")
        return json.dumps({"status": "completed", "file_path": final_file_path})
    except Exception as e:
        err_msg = str(e)
        if progress_callback:
            progress_callback("failed", 0.0, "—", "—", "—", "—", err_msg)
        return json.dumps({"status": "failed", "error": err_msg})
