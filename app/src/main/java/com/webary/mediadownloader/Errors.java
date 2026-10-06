package com.webary.mediadownloader;

import java.util.Locale;

/** Turns yt-dlp / FFmpeg output into one short sentence a person can act on. */
final class Errors {
    private Errors() {}

    static String friendly(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "Something went wrong. Try again.";
        // Our own FFmpeg failures lead with a summary line, followed by FFmpeg's log.
        String first = raw.trim().split("\\r?\\n")[0];
        if (first.startsWith("FFmpeg could not combine")) return "Couldn't combine the audio and video.";
        if (first.startsWith("FFmpeg could not create")) return "Couldn't convert the audio.";
        if (first.startsWith("Could not convert captions")) return "Couldn't convert the captions to SRT.";
        String line = keyLine(raw);
        String l = line.toLowerCase(Locale.US);

        if (l.contains("sign in to confirm") || l.contains("not a bot"))
            return "YouTube is asking for a sign-in check. Try again in a while.";
        if (l.contains("private video")) return "This video is private.";
        if (l.contains("members-only") || l.contains("join this channel")) return "This video is for channel members only.";
        if (l.contains("age") && l.contains("confirm")) return "This video is age-restricted and needs a sign-in.";
        if (l.contains("video unavailable") || l.contains("this video has been removed") || l.contains("is not available"))
            return "This video is unavailable.";
        if (l.contains("requested format is not available"))
            return "That quality or format isn't offered for this video. Try Best.";
        if (l.contains("unsupported url")) return "This site or link isn't supported.";
        if (l.contains("http error 403") || l.contains("403: forbidden"))
            return "The site refused the download (403). Try again — the engine updates automatically.";
        if (l.contains("http error 429") || l.contains("too many requests"))
            return "The site is rate-limiting downloads. Wait a bit and retry.";
        if (l.contains("unable to download webpage") || l.contains("failed to resolve") || l.contains("timed out")
                || l.contains("network is unreachable") || l.contains("connection"))
            return "Couldn't reach the site. Check your connection and retry.";
        if (l.contains("no space left")) return "Your phone is out of storage.";
        if (l.contains("ffmpeg")) return "Couldn't combine the audio and video.";

        if (line.length() > 160) line = line.substring(0, 160) + "…";
        return line;
    }

    /** Last "ERROR:" line, else the last line that isn't a warning. */
    static String keyLine(String raw) {
        String[] lines = raw.split("\\r?\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String s = lines[i].trim();
            if (s.startsWith("ERROR:")) return clean(s.substring(6));
        }
        for (int i = lines.length - 1; i >= 0; i--) {
            String s = lines[i].trim();
            if (!s.isEmpty() && !s.startsWith("WARNING:") && !s.startsWith("[")) return clean(s);
        }
        return clean(lines[lines.length - 1]);
    }

    private static String clean(String s) {
        s = s.trim();
        // Drop "[youtube] abc123: " style prefixes.
        if (s.startsWith("[")) {
            int colon = s.indexOf(": ");
            if (colon > 0 && colon < 40) s = s.substring(colon + 2);
        }
        return s;
    }
}
