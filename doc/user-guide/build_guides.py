"""Build annotated screenshots, manifest metadata, and offline EN/SI Glide guides."""
from __future__ import annotations

import argparse
import hashlib
import html
import json
import math
import re
import shutil
from pathlib import Path
from typing import Any

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent
ASSETS = ROOT / "assets"
ANNOTATED = ASSETS / "annotated"
MANIFEST_PATH = ROOT / "screenshot-manifest.json"

SLUGS: dict[str, list[str]] = {
    "Windows": [
        "send-empty", "receive-ready", "activity-history", "pair-find",
        "pair-enter-code", "pair-complete", "pair-show-qr", "pair-enter-link",
        "pair-by-address", "settings", "send-qr-dialog", "send-paired-picker",
        "receive-large-qr", "pair-approval", "receive-offer",
        "activity-cancelled", "activity-receiving",
    ],
    "Linux": [
        "receive-ready", "send-empty", "activity-history", "pair-find",
        "pair-show-qr", "pair-enter-link", "pair-by-address", "settings-upper",
        "settings-lower", "pair-not-found", "pair-enter-code", "pair-complete",
        "send-qr-dialog", "send-staged", "activity-sending", "receive-offer",
    ],
    "Android": [
        "home", "send-select-empty", "send-select-staged",
        "recipient-bluetooth-off", "recipient-wifi-off", "recipient-no-devices",
        "send-qr-confirmation", "send-progress", "nearby-verify", "receive-offer",
        "receive-complete", "recipient-list", "nearby-verify-light", "history",
        "settings-appearance", "settings-trust", "settings-storage",
        "recipient-list-duplicate", "nearby-verify-light-duplicate",
    ],
}

TITLES: dict[str, list[str]] = {
    "en": [
        "Quick start and getting started",
        "Screen guide",
        "Pair devices",
        "Send and receive",
        "Find received files",
        "Activity and History",
        "Settings",
        "Troubleshooting",
        "FAQ, limits, and glossary",
        "Appendix: Every control, every screen",
    ],
    "si": [
        "ආරම්භය සහ පළමු file යැවීම",
        "තිර හඳුනාගැනීම",
        "Devices pair කිරීම",
        "Files යැවීම සහ ලැබීම",
        "ලැබුණු files සොයාගැනීම",
        "Activity සහ History",
        "Settings සහ සැකසුම්",
        "ගැටලු විසඳීම",
        "නිතර අසන ප්‍රශ්න, සීමාවන් සහ වචන මාලාව",
        "උපග්‍රන්ථය: සෑම තිරයකම ඇති සියලුම controls",
    ],
}

LIGHT_STATES = {
    ("android", "recipient-list"),
    ("android", "nearby-verify-light"),
}

EXPIRED_STAMP_STATES = {
    ("windows", "receive-ready"),
    ("windows", "receive-large-qr"),
    ("windows", "send-qr-dialog"),
    ("windows", "pair-show-qr"),
    ("windows", "pair-enter-code"),
    ("linux", "receive-ready"),
    ("linux", "send-qr-dialog"),
    ("linux", "pair-show-qr"),
    ("linux", "pair-enter-code"),
    ("android", "nearby-verify"),
    ("android", "nearby-verify-light"),
}

SINHALA_RE = re.compile(r"[\u0D80-\u0DFF]")

# Every unique screenshot has structured callout geometry + EN/SI labels/descriptions.
# Format: (platform_lower, slug): { "title_en", "title_si", "alt_en", "alt_si", "callouts": [...] }
CALLOUT_SPECS: dict[tuple[str, str], dict[str, Any]] = {
    # ---------------- WINDOWS (17) ----------------
    ("windows", "send-empty"): {
        "title_en": "Windows Send screen (ready to select files)",
        "title_si": "Windows Send තිරය (files තේරීමට සූදානම්)",
        "alt_en": "Windows Glide window on the Send tab showing Add files, Add folder, Quick share with QR, Paired device, Sharing is local, Received files, and Settings.",
        "alt_si": "Windows Glide හි Send පිටුව: Add files, Add folder, Quick share with QR, Paired device, Sharing is local, Received files සහ Settings පෙන්වන තිරය.",
        "callouts": [
            {"n": 1, "box": [21, 148, 224, 226], "pos": "right", "label_en": "Navigation sidebar", "desc_en": "Switch between Send, Receive, Activity, and Pair devices.", "label_si": "Navigation sidebar (වම් මෙනුව)", "desc_si": "Send, Receive, Activity සහ Pair devices අතර මාරු වීමට භාවිතා කරයි."},
            {"n": 2, "box": [625, 336, 145, 50], "pos": "left", "label_en": "Add files", "desc_en": "Pick one or more files from Windows Explorer to queue for sending.", "label_si": "Add files", "desc_si": "යැවීමට අවශ්‍ය file එකක් හෝ කිහිපයක් Windows Explorer මඟින් තෝරයි."},
            {"n": 3, "box": [780, 336, 145, 50], "pos": "right", "label_en": "Add folder", "desc_en": "Queue an entire folder while keeping its subfolder structure.", "label_si": "Add folder", "desc_si": "සම්පූර්ණ folder එකක් එහි ඇති අනු-folders සමඟම යැවීමට එකතු කරයි."},
            {"n": 4, "box": [332, 538, 885, 78], "pos": "left", "label_en": "Quick share with QR", "desc_en": "Start a one-time encrypted transfer by showing a QR code for a phone to scan.", "label_si": "Quick share with QR", "desc_si": "Phone එකකින් scan කර එක් වරක් පමණක් file ලබා ගැනීමට තාවකාලික QR code එකක් පෙන්වයි."},
            {"n": 5, "box": [332, 625, 885, 78], "pos": "left", "label_en": "Paired device", "desc_en": "Choose a previously paired PC or phone to send directly without scanning a QR.", "label_si": "Paired device", "desc_si": "පෙර pair කර ඇති PC හෝ phone එකකට QR නැවත scan නොකර කෙලින්ම යවයි."},
            {"n": 6, "box": [21, 636, 224, 92], "pos": "right", "label_en": "Sharing is local", "desc_en": "Confirms Glide uses your local Wi-Fi or Ethernet only, with no cloud servers.", "label_si": "Sharing is local", "desc_si": "Transfers සියල්ල ඔබේ local network එක තුළම සිදු වන බවත් cloud හරහා නොයන බවත් දන්වයි."},
            {"n": 7, "box": [21, 745, 224, 53], "pos": "right", "label_en": "Settings", "desc_en": "Change appearance, save folder, connection details, and trusted devices.", "label_si": "Settings", "desc_si": "Theme, ලැබෙන files save වන folder එක සහ trusted devices කළමනාකරණය කරයි."},
            {"n": 8, "box": [1081, 73, 162, 68], "pos": "left", "label_en": "Received files", "desc_en": "Header shortcut that opens your received-files folder in File Explorer.", "label_si": "Received files", "desc_si": "ලැබුණු files save වන folder එක ඉක්මනින් File Explorer හි විවෘත කරයි."},
        ],
    },
    ("windows", "receive-ready"): {
        "title_en": "Windows Receive screen (listening active)",
        "title_si": "Windows Receive තිරය (ලැබීමට සූදානම්)",
        "alt_en": "Windows Glide Receive tab showing Stop receiving button, transfer QR code, Show larger code, Copy receive link, and Refresh code.",
        "alt_si": "Windows Glide හි Receive පිටුව: Stop receiving, transfer QR code, Show larger code, Copy receive link සහ Refresh code බොත්තම් පෙන්වයි.",
        "callouts": [
            {"n": 1, "box": [21, 206, 224, 53], "pos": "right", "label_en": "Receive tab", "desc_en": "Opens the Windows receiver controls and one-time phone transfer QR.", "label_si": "Receive පිටුව", "desc_si": "Windows receiver පාලන සහ phone එකකින් files එවීමට QR එක මෙහි ඇත."},
            {"n": 2, "box": [332, 322, 137, 50], "pos": "right", "label_en": "Start receiving / Stop receiving", "desc_en": "Toggles listening on this PC. Shows Start receiving when paused and Stop receiving while active.", "label_si": "Start receiving / Stop receiving", "desc_si": "PC එක files භාරගැනීමට සූදානම් කරයි හෝ නවත්වයි (Windows හි Start/Stop receiving ලෙස පෙනේ)."},
            {"n": 3, "box": [332, 507, 253, 253], "pos": "left", "label_en": "One-time transfer QR", "desc_en": "Scan this with Glide Mobile (Send files) to send directly to this Windows PC.", "label_si": "Transfer QR code", "desc_si": "Phone එකෙන් Send files තෝරා මෙම QR එක scan කළ විට කෙලින්ම PC එකට files එවිය හැක."},
            {"n": 4, "box": [610, 551, 240, 50], "pos": "right", "label_en": "Show larger code", "desc_en": "Enlarges the QR code for easier camera scanning from farther away.", "label_si": "Show larger code", "desc_si": "Camera එකෙන් පහසුවෙන් scan කිරීමට QR code එක විශාල කර පෙන්වයි."},
            {"n": 5, "box": [610, 611, 240, 50], "pos": "right", "label_en": "Copy receive link", "desc_en": "Copies the temporary glide:// invitation URI to paste into another device.", "label_si": "Copy receive link", "desc_si": "තාවකාලික glide:// ලිපිනය copy කර අනෙක් device එකේ paste කිරීමට ලබා දෙයි."},
            {"n": 6, "box": [610, 671, 240, 50], "pos": "right", "label_en": "Refresh code", "desc_en": "Replaces an expired or previously used QR code with a fresh one-time code.", "label_si": "Refresh code", "desc_si": "කල් ඉකුත් වූ හෝ භාවිතා කළ QR code එක වෙනුවට අලුත් එකක් සාදයි."},
        ],
    },
    ("windows", "receive-large-qr"): {
        "title_en": "Windows enlarged Receive QR dialog",
        "title_si": "Windows විශාල කළ Receive QR කවුළුව",
        "alt_en": "Windows Scan with Glide Mobile modal showing large transfer QR code, Refresh, Copy link, and Close.",
        "alt_si": "Windows හි Scan with Glide Mobile කවුළුව: විශාල QR code එක, Refresh, Copy link සහ Close බොත්තම් පෙන්වයි.",
        "callouts": [
            {"n": 1, "box": [740, 324, 440, 441], "pos": "left", "label_en": "Enlarged transfer QR", "desc_en": "High-contrast QR opened by Show larger code for reliable phone camera scanning.", "label_si": "විශාල transfer QR code එක", "desc_si": "දුර සිට හෝ අඩු ආලෝකයේදී පවා phone camera එකෙන් පහසුවෙන් scan කිරීමට විශාල කර ඇත."},
            {"n": 2, "box": [817, 787, 88, 50], "pos": "left", "label_en": "Refresh", "desc_en": "Generates a fresh one-time receive QR code immediately.", "label_si": "Refresh", "desc_si": "අලුත් තාවකාලික QR code එකක් එසැණින් සාදයි."},
            {"n": 3, "box": [915, 787, 101, 50], "pos": "bottom", "label_en": "Copy link", "desc_en": "Copies the same receive invitation URI for camera-free pasting on Android.", "label_si": "Copy link", "desc_si": "Camera නොමැති විට Android හි Paste invitation වෙත යෙදීමට link එක copy කරයි."},
            {"n": 4, "box": [1026, 787, 77, 50], "pos": "right", "label_en": "Close", "desc_en": "Closes the enlarged dialog while keeping Receive mode active.", "label_si": "Close", "desc_si": "Receive සූදානම නොනවත්වා මෙම විශාල කවුළුව වසයි."},
        ],
    },
    ("windows", "send-qr-dialog"): {
        "title_en": "Windows Quick share with QR dialog (sender waiting)",
        "title_si": "Windows Quick share with QR කවුළුව (යවන්නා රැඳී සිටින අවස්ථාව)",
        "alt_en": "Windows Send tab with Quick share QR dialog displaying QR code, Refresh code, and Cancel QR share.",
        "alt_si": "Windows Send තිරයේ Quick share QR කවුළුව: QR code එක, Refresh code සහ Cancel QR share පෙන්වයි.",
        "callouts": [
            {"n": 1, "box": [1530, 194, 324, 50], "pos": "left", "label_en": "Staged batch toolbar", "desc_en": "Behind the dialog, Add files, Add folder, and Clear list manage queued items.", "label_si": "තෝරාගත් files පාලනය", "desc_si": "Add files, Add folder සහ Clear list මඟින් යැවීමට තැබූ files ලැයිස්තුව වෙනස් කළ හැක."},
            {"n": 2, "box": [782, 379, 356, 356], "pos": "left", "label_en": "Sender QR code", "desc_en": "Receiver scans this with Glide Mobile (Receive files → Scan PC QR code). Next, Windows asks you to Confirm receiving device → Send files.", "label_si": "යැවීමේ QR code එක", "desc_si": "ලබන phone එක Receive files → Scan PC QR code හරහා මෙය scan කළ පසු, Windows හි Confirm receiving device → Send files එබිය යුතුය."},
            {"n": 3, "box": [755, 787, 200, 50], "pos": "left", "label_en": "Refresh code", "desc_en": "Generates a new temporary QR if the previous one timed out.", "label_si": "Refresh code", "desc_si": "පෙර code එක කල් ඉකුත් වූවා නම් අලුත් තාවකාලික QR එකක් සාදයි."},
            {"n": 4, "box": [965, 787, 200, 50], "pos": "right", "label_en": "Cancel QR share", "desc_en": "Aborts the one-time QR session without removing your selected files.", "label_si": "Cancel QR share", "desc_si": "තෝරාගත් files ඉවත් නොකර QR share සැසිය නවතා දමයි."},
        ],
    },
    ("windows", "send-paired-picker"): {
        "title_en": "Windows Paired device picker modal",
        "title_si": "Windows Paired device තෝරන කවුළුව",
        "alt_en": "Windows modal listing saved trusted devices with Pair a new device and Close buttons.",
        "alt_si": "Windows හි save කර ඇති devices ලැයිස්තුව සමඟ Pair a new device සහ Close බොත්තම් පෙන්වන කවුළුව.",
        "callouts": [
            {"n": 1, "box": [696, 510, 528, 60], "pos": "left", "label_en": "Saved paired device", "desc_en": "Click a saved device card to connect and offer your selected files directly.", "label_si": "Save කළ device එක", "desc_si": "මෙම device එක මත click කළ විට QR නොමැතිව කෙලින්ම සම්බන්ධ වී files යැවීමට අසයි."},
            {"n": 2, "box": [696, 630, 155, 50], "pos": "left", "label_en": "Pair a new device", "desc_en": "Jumps directly to Pair devices if your recipient is not listed yet.", "label_si": "Pair a new device", "desc_si": "අවශ්‍ය device එක ලැයිස්තුවේ නැතිනම් කෙලින්ම Pair devices පිටුවට යයි."},
            {"n": 3, "box": [1147, 630, 77, 50], "pos": "right", "label_en": "Close", "desc_en": "Returns to the Send queue without connecting.", "label_si": "Close", "desc_si": "සම්බන්ධ නොවී Send පිටුවට ආපසු යයි."},
        ],
    },
    ("windows", "receive-offer"): {
        "title_en": "Windows incoming transfer offer prompt",
        "title_si": "Windows වෙත ලැබෙන transfer අනුමත කිරීමේ තිරය",
        "alt_en": "Windows Incoming transfer modal showing sender name, item summary, Decline, and Accept transfer.",
        "alt_si": "Windows Incoming transfer කවුළුව: යවන්නාගේ නම, file විස්තරය, Decline සහ Accept transfer බොත්තම් පෙන්වයි.",
        "callouts": [
            {"n": 1, "box": [320, 280, 390, 128], "pos": "left", "label_en": "Incoming transfer summary", "desc_en": "Verify the sender name, file count, and total size before accepting anything.", "label_si": "ලැබෙන file විස්තරය", "desc_si": "Files භාරගැනීමට පෙර යවන්නාගේ නම, file ගණන සහ ප්‍රමාණය පරීක්ෂා කරන්න."},
            {"n": 2, "box": [307, 420, 200, 40], "pos": "left", "label_en": "Decline", "desc_en": "Refuses the transfer immediately; zero bytes are written to your disk.", "label_si": "Decline", "desc_si": "Transfer එක ප්‍රතික්ෂේප කරයි; කිසිදු file එකක් PC එකට save නොවේ."},
            {"n": 3, "box": [517, 418, 203, 44], "pos": "right", "label_en": "Accept transfer", "desc_en": "Required explicit consent before Glide receives, verifies SHA-256, and saves the files.", "label_si": "Accept transfer", "desc_si": "ඔබ මෙය එබූ පසු පමණක් files ලැබී, SHA-256 පරීක්ෂා කර folder එකට save වේ."},
        ],
    },
    ("windows", "pair-find"): {
        "title_en": "Windows Pair devices — Find devices view",
        "title_si": "Windows Pair devices — ළඟ ඇති devices සෙවීම",
        "alt_en": "Windows Pair devices screen with Find devices, Show my QR and code, Enter pairing link, Pair by address tabs, discovered devices list, and Remove button.",
        "alt_si": "Windows Pair devices පිටුව: Find devices, Show my QR and code, Enter pairing link, Pair by address සහ Remove බොත්තම පෙන්වයි.",
        "callouts": [
            {"n": 1, "box": [332, 251, 129, 50], "pos": "left", "label_en": "Find devices", "desc_en": "Scans your local network over mDNS to discover nearby desktop peers ready to pair.", "label_si": "Find devices", "desc_si": "එකම Wi-Fi/LAN ජාලයේ pair වීමට සූදානම් අනෙක් computers සොයයි."},
            {"n": 2, "box": [471, 251, 191, 50], "pos": "bottom", "label_en": "Show my QR and code", "desc_en": "Displays your pair-only QR code and 6-digit PIN for another device to connect.", "label_si": "Show my QR and code", "desc_si": "අනෙක් device එකට සම්බන්ධ වීම සඳහා ඔබේ pair-only QR code එක සහ ඉලක්කම් 6 කේතය පෙන්වයි."},
            {"n": 3, "box": [672, 251, 154, 50], "pos": "top", "label_en": "Enter pairing link", "desc_en": "Paste a glide://pair/v2 link copied from another device.", "label_si": "Enter pairing link", "desc_si": "අනෙක් device එකෙන් copy කළ pairing link එක paste කර pair වීමට යොදාගනී."},
            {"n": 4, "box": [836, 251, 140, 50], "pos": "right", "label_en": "Pair by address", "desc_en": "Fallback mode when local mDNS discovery is blocked: enter IP:port and 6-digit code.", "label_si": "Pair by address", "desc_si": "ස්වයංක්‍රීය සෙවීම අසාර්ථක නම් IP:port සහ ඉලක්කම් 6 කේතය යොදා pair වේ."},
            {"n": 5, "box": [332, 358, 885, 50], "pos": "left", "label_en": "Discovered device row", "desc_en": "Click a discovered desktop peer to open the 6-digit pairing code entry.", "label_si": "හමු වූ device එක", "desc_si": "මෙය මත click කළ විට ඉලක්කම් 6 කේතය ඇතුළු කිරීමේ පියවරට යයි."},
            {"n": 6, "box": [1125, 535, 92, 50], "pos": "left", "label_en": "Remove", "desc_en": "Revokes trust from an existing paired device so it must pair again next time.", "label_si": "Remove", "desc_si": "දැනට pair කර ඇති device එකක විශ්වාසනීය අවසරය ඉවත් කරයි."},
        ],
    },
    ("windows", "pair-show-qr"): {
        "title_en": "Windows Pair devices — Show my QR and code",
        "title_si": "Windows Pair devices — Pair-only QR සහ කේතය පෙන්වීම",
        "alt_en": "Windows Show my QR and code tab displaying pair-only QR, 6-digit PIN, Copy link, New code, and Cancel.",
        "alt_si": "Windows Show my QR and code පිටුව: pair-only QR, ඉලක්කම් 6 කේතය, Copy link, New code සහ Cancel පෙන්වයි.",
        "callouts": [
            {"n": 1, "box": [466, 251, 194, 50], "pos": "top", "label_en": "Pair-only mode", "desc_en": "Remember: this QR only saves trusted identities for later; it does not send files.", "label_si": "Pair-only ක්‍රමය", "desc_si": "මතක තබාගන්න: මෙම QR එක trust save කිරීමට පමණි; මෙයින් files යැවෙන්නේ නැත."},
            {"n": 2, "box": [332, 360, 326, 326], "pos": "left", "label_en": "Pair-only QR", "desc_en": "Scan with Glide Mobile (Pair device) or share with another desktop.", "label_si": "Pair-only QR code", "desc_si": "Phone එකේ Pair device තුළින් මෙය scan කර device දෙක හඳුනාගැන්විය හැක."},
            {"n": 3, "box": [674, 475, 253, 111], "pos": "right", "label_en": "6-digit pairing code", "desc_en": "Read these 6 digits to the other computer to type into Find devices or Pair by address.", "label_si": "ඉලක්කම් 6 කේතය", "desc_si": "අනෙක් පරිගණකයේ Find devices හෝ Pair by address තුළ මෙම ඉලක්කම් 6 ඇතුළු කළ යුතුය."},
            {"n": 4, "box": [332, 701, 101, 50], "pos": "left", "label_en": "Copy link", "desc_en": "Copies the temporary glide://pair invitation link to your clipboard.", "label_si": "Copy link", "desc_si": "තාවකාලික pairing link එක copy කරගනී."},
            {"n": 5, "box": [443, 701, 106, 50], "pos": "bottom-right", "label_en": "New code", "desc_en": "Generates a fresh 6-digit PIN and QR when the current invitation expires.", "label_si": "New code", "desc_si": "පෙර කේතය කල් ඉකුත් වුවහොත් අලුත් ඉලක්කම් 6 කේතයක් සහ QR එකක් සාදයි."},
            {"n": 6, "box": [559, 701, 82, 50], "pos": "right", "label_en": "Cancel", "desc_en": "Ends pairing advertisement immediately.", "label_si": "Cancel", "desc_si": "Pair වීමේ ආරාධනාව නවත්වයි."},
        ],
    },
    ("windows", "pair-enter-code"): {
        "title_en": "Windows pairing — 6-digit code entry",
        "title_si": "Windows pairing — ඉලක්කම් 6 කේතය ඇතුළු කිරීම",
        "alt_en": "Windows Pair devices screen with 6-digit code input box, Pair button, and Cancel button.",
        "alt_si": "Windows Pair devices හි ඉලක්කම් 6 කේතය ඇතුළු කරන කොටුව, Pair සහ Cancel බොත්තම් පෙන්වන තිරය.",
        "callouts": [
            {"n": 1, "box": [332, 390, 225, 62], "pos": "right", "label_en": "6-digit code box", "desc_en": "Type the 6-digit code currently shown on the receiving computer.", "label_si": "කේතය ලියන කොටුව", "desc_si": "අනෙක් පරිගණකයේ පෙන්වන ඉලක්කම් 6 කේතය මෙහි type කරන්න."},
            {"n": 2, "box": [332, 467, 66, 50], "pos": "left", "label_en": "Pair", "desc_en": "Starts the mutual SPKI-verified handshake with the peer.", "label_si": "Pair", "desc_si": "දෙපසම තහවුරු කරන ආරක්ෂිත pair වීම ආරම්භ කරයි."},
            {"n": 3, "box": [408, 467, 82, 50], "pos": "right", "label_en": "Cancel", "desc_en": "Backs out to device discovery without saving trust.", "label_si": "Cancel", "desc_si": "Pair නොවී ආපසු සෙවීමේ ලැයිස්තුවට යයි."},
        ],
    },
    ("windows", "pair-approval"): {
        "title_en": "Windows pairing approval prompt",
        "title_si": "Windows pairing අනුමැතිය ලබා දීම",
        "alt_en": "Windows pairing confirmation prompt displaying incoming peer name, fingerprint context, Approve, and Decline buttons.",
        "alt_si": "Windows හි අනෙක් device එකේ නම සමඟ Approve සහ Decline බොත්තම් පෙන්වන pairing අනුමත කිරීමේ තිරය.",
        "callouts": [
            {"n": 1, "box": [259, 257, 291, 32], "pos": "right", "label_en": "Incoming pairing request", "desc_en": "Confirm the connecting device name matches your other device.", "label_si": "සම්බන්ධ වන device එක", "desc_si": "පෙන්වන නම ඔබ සම්බන්ධ කිරීමට උත්සාහ කරන අනෙක් device එකමදැයි බලන්න."},
            {"n": 2, "box": [264, 295, 82, 43], "pos": "left", "label_en": "Approve", "desc_en": "Saves this device in Trusted devices on both sides.", "label_si": "Approve", "desc_si": "මෙම device එක Trusted devices ලැයිස්තුවට save කිරීමට අනුමැතිය දෙයි."},
            {"n": 3, "box": [352, 297, 71, 40], "pos": "right", "label_en": "Decline", "desc_en": "Rejects an unexpected or unrecognized pairing request.", "label_si": "Decline", "desc_si": "නොදන්නා හෝ වැරදි pairing ඉල්ලීමක් ප්‍රතික්ෂේප කරයි."},
        ],
    },
    ("windows", "pair-complete"): {
        "title_en": "Windows pairing complete",
        "title_si": "Windows pairing සාර්ථකව අවසන් වීම",
        "alt_en": "Windows Pair devices screen confirming Paired status, Done button, and updated Paired devices list.",
        "alt_si": "Windows Pair devices තිරයේ සාර්ථකව pair වූ බව පෙන්වන තත්ත්වය, Done බොත්තම සහ Paired devices ලැයිස්තුව.",
        "callouts": [
            {"n": 1, "box": [327, 325, 145, 25], "pos": "right", "label_en": "Pairing status", "desc_en": "Shows confirmation once both peers store each other's public key fingerprint.", "label_si": "සාර්ථක තත්ත්වය", "desc_si": "Device දෙකම එකිනෙකාගේ identity එක ආරක්ෂිතව save කළ බව පෙන්වයි."},
            {"n": 2, "box": [332, 361, 74, 50], "pos": "right", "label_en": "Done", "desc_en": "Completes pairing and returns you to the ready Pair devices view.", "label_si": "Done", "desc_si": "සම්පූර්ණ වූ පණිවිඩය වසා Pair devices පිටුවට යයි."},
            {"n": 3, "box": [328, 535, 891, 54], "pos": "left", "label_en": "Paired devices entry", "desc_en": "The newly paired device now appears under Paired devices for direct future sends.", "label_si": "Paired devices ලැයිස්තුව", "desc_si": "අලුතින් pair කළ device එක මෙහි පෙනෙන අතර, ඉදිරියේදී Send → Paired device හරහා කෙලින්ම යැවිය හැක."},
        ],
    },
    ("windows", "pair-enter-link"): {
        "title_en": "Windows pairing — Enter pairing link",
        "title_si": "Windows pairing — Pairing link එක paste කිරීම",
        "alt_en": "Windows Enter pairing link tab showing URI paste field, Pair, and Cancel buttons.",
        "alt_si": "Windows Enter pairing link පිටුව: link එක paste කරන කොටුව, Pair සහ Cancel බොත්තම් පෙන්වයි.",
        "callouts": [
            {"n": 1, "box": [667, 251, 156, 50], "pos": "top", "label_en": "Enter pairing link tab", "desc_en": "Alternative when mDNS discovery misses a device but you copied its invite link.", "label_si": "Enter pairing link පිටුව", "desc_si": "Device එක ස්වයංක්‍රීයව හමු නොවන විට copy කළ link එකකින් pair වීමට භාවිතා කරයි."},
            {"n": 2, "box": [332, 355, 885, 63], "pos": "left", "label_en": "Invitation link box", "desc_en": "Paste the glide://pair URI copied from the other computer.", "label_si": "Invitation link කොටුව", "desc_si": "අනෙක් device එකෙන් copy කළ glide://pair සම්බන්ධතා ලිපිනය මෙහි paste කරන්න."},
            {"n": 3, "box": [332, 428, 66, 50], "pos": "left", "label_en": "Pair", "desc_en": "Validates the one-time link and starts mutual approval.", "label_si": "Pair", "desc_si": "ලිපිනය පරීක්ෂා කර pair වීම ආරම්භ කරයි."},
            {"n": 4, "box": [408, 428, 82, 50], "pos": "right", "label_en": "Cancel", "desc_en": "Clears the form without connecting.", "label_si": "Cancel", "desc_si": "සම්බන්ධ නොවී ආපසු යයි."},
        ],
    },
    ("windows", "pair-by-address"): {
        "title_en": "Windows pairing — Pair by address",
        "title_si": "Windows pairing — IP ලිපිනය මඟින් pair වීම",
        "alt_en": "Windows Pair by address tab with host/port input, 6-digit pairing code input, Pair, and Cancel.",
        "alt_si": "Windows Pair by address පිටුව: IP ලිපිනය/port කොටුව, ඉලක්කම් 6 කේතය, Pair සහ Cancel පෙන්වයි.",
        "callouts": [
            {"n": 1, "box": [831, 251, 142, 50], "pos": "top", "label_en": "Pair by address tab", "desc_en": "Connects directly over LAN IP/port when routers or firewalls block mDNS discovery.", "label_si": "Pair by address පිටුව", "desc_si": "Wi-Fi හි device සෙවීම (mDNS) අවහිර නම් කෙලින්ම IP:port මඟින් pair වේ."},
            {"n": 2, "box": [332, 353, 400, 50], "pos": "right", "label_en": "Address & port box", "desc_en": "Enter the target IPv4 address and listening port (shown in the other device's Settings).", "label_si": "IP ලිපිනය සහ port", "desc_si": "අනෙක් device එකේ Settings හි පෙන්වන IP ලිපිනය සහ port අංකය මෙහි ලියන්න."},
            {"n": 3, "box": [332, 440, 400, 51], "pos": "right", "label_en": "6-digit code box", "desc_en": "Enter the active 6-digit PIN from Show my QR and code on the target computer.", "label_si": "ඉලක්කම් 6 කේතය", "desc_si": "අනෙක් පරිගණකයේ Show my QR and code තුළ පෙන්වන ඉලක්කම් 6 කේතය ඇතුළු කරන්න."},
            {"n": 4, "box": [332, 506, 66, 50], "pos": "left", "label_en": "Pair", "desc_en": "Connects directly to that IP:port and verifies the one-time token.", "label_si": "Pair", "desc_si": "එම IP ලිපිනය වෙත කෙලින්ම සම්බන්ධ වී pair වීම අරඹයි."},
            {"n": 5, "box": [408, 506, 82, 50], "pos": "right", "label_en": "Cancel", "desc_en": "Cancels manual address entry.", "label_si": "Cancel", "desc_si": "ඇතුළු කිරීම නවතා දමයි."},
        ],
    },
    ("windows", "activity-receiving"): {
        "title_en": "Windows Activity — receiving in progress",
        "title_si": "Windows Activity — File ලැබෙමින් පවතින අවස්ථාව",
        "alt_en": "Windows Activity screen showing live incoming transfer progress bar, file percentage, speed, and Cancel button.",
        "alt_si": "Windows Activity තිරය: ලැබෙන file එකේ ප්‍රගතිය, වේගය සහ Cancel බොත්තම පෙන්වයි.",
        "callouts": [
            {"n": 1, "box": [245, 177, 634, 148], "pos": "left", "label_en": "Live transfer progress card", "desc_en": "Shows the current file, byte progress, transfer speed, and verification stage.", "label_si": "සජීවී ප්‍රගති කාඩ්පත", "desc_si": "දැනට ලැබෙමින් පවතින file එක, ප්‍රතිශතය, වේගය සහ පරීක්ෂා කිරීමේ තත්ත්වය පෙන්වයි."},
            {"n": 2, "box": [889, 226, 66, 54], "pos": "right", "label_en": "Cancel", "desc_en": "Stops an in-flight transfer safely; partial staged files are discarded.", "label_si": "Cancel", "desc_si": "සිදුවෙමින් පවතින transfer එක ආරක්ෂිතව නවතයි; අසම්පූර්ණ කොටස් folder එකට save නොවේ."},
        ],
    },
    ("windows", "activity-history"): {
        "title_en": "Windows Activity — verified transfer history",
        "title_si": "Windows Activity — සම්පූර්ණ වූ transfers ඉතිහාසය",
        "alt_en": "Windows Activity tab showing Received files, Clear history, Search history, All/Sent/Received filters, Completed badge, and Open folder.",
        "alt_si": "Windows Activity පිටුව: Received files, Clear history, Search history, All/Sent/Received, Completed සහ Open folder පෙන්වයි.",
        "callouts": [
            {"n": 1, "box": [948, 73, 162, 68], "pos": "left", "label_en": "Received files", "desc_en": "Opens the configured receive destination folder in Windows Explorer.", "label_si": "Received files", "desc_si": "ලැබුණු files ඇති folder එක Windows Explorer හි විවෘත කරයි."},
            {"n": 2, "box": [1120, 73, 123, 68], "pos": "bottom", "label_en": "Clear history", "desc_en": "Removes history log rows from Activity without deleting your actual saved files.", "label_si": "Clear history", "desc_si": "Save වූ files මකා නොදමා, Activity ඉතිහාස ලැයිස්තුව පමණක් පිරිසිදු කරයි."},
            {"n": 3, "box": [816, 350, 181, 50], "pos": "left", "label_en": "Search history", "desc_en": "Filters history rows by device name or file summary.", "label_si": "Search history", "desc_si": "Device නම හෝ file නම අනුව පැරණි transfers සෙවීමට භාවිතා කරයි."},
            {"n": 4, "box": [1005, 350, 191, 50], "pos": "top", "label_en": "All / Sent / Received", "desc_en": "Switches between all records, sent transfers only, or received transfers only.", "label_si": "All / Sent / Received", "desc_si": "සියලුම වාර්තා, යැවූ දේ පමණක්, හෝ ලැබුණු දේ පමණක් වෙන වෙනම බැලීමට."},
            {"n": 5, "box": [934, 452, 89, 28], "pos": "top", "label_en": "Completed", "desc_en": "Indicates every item passed SHA-256 verification and committed cleanly.", "label_si": "Completed", "desc_si": "සෑම file එකකම SHA-256 පරීක්ෂාව සාර්ථකව සිදු වී save වූ බව තහවුරු කරයි."},
            {"n": 6, "box": [1043, 441, 119, 50], "pos": "right", "label_en": "Open folder", "desc_en": "Jumps directly to the folder containing that received transfer.", "label_si": "Open folder", "desc_si": "එම ලැබුණු file එක ඇති ස්ථානය කෙලින්ම විවෘත කරයි."},
        ],
    },
    ("windows", "activity-cancelled"): {
        "title_en": "Windows Activity — cancelled transfer details",
        "title_si": "Windows Activity — අවලංගු කළ transfer විස්තර",
        "alt_en": "Windows Activity screen displaying a Cancelled transfer status card and expandable Technical details link.",
        "alt_si": "Windows Activity තිරයේ Cancelled තත්ත්වය සහ Technical details බොත්තම පෙන්වන අවස්ථාව.",
        "callouts": [
            {"n": 1, "box": [258, 207, 164, 78], "pos": "right", "label_en": "Cancelled result", "desc_en": "Shows the transfer was aborted cleanly before completion.", "label_si": "Cancelled තත්ත්වය", "desc_si": "Transfer එක සම්පූර්ණ වීමට පෙර අවලංගු වූ බව පෙන්වයි."},
            {"n": 2, "box": [266, 291, 124, 26], "pos": "right", "label_en": "Technical details", "desc_en": "Expand to copy error diagnostics if a transfer failed unexpectedly.", "label_si": "Technical details", "desc_si": "දෝෂයක් ඇති වූ විට ගැටලුව හඳුනාගැනීමට තාක්ෂණික විස්තර විවෘත කර බැලිය හැක."},
        ],
    },
    ("windows", "settings"): {
        "title_en": "Windows Settings screen",
        "title_si": "Windows Settings (සැකසුම්) තිරය",
        "alt_en": "Windows Glide Settings screen showing Appearance, Save received files to with Change folder, Advanced connection details, Trusted devices Remove button, and Received files header button.",
        "alt_si": "Windows Settings තිරය: Appearance, Save received files to, Change folder, Advanced connection details සහ Trusted devices Remove පෙන්වයි.",
        "callouts": [
            {"n": 1, "box": [331, 298, 225, 45], "pos": "right", "label_en": "Appearance", "desc_en": "Choose System, Light, or Dark theme for the Windows app.", "label_si": "Appearance", "desc_si": "Windows යෙදුම සඳහා System, Light හෝ Dark වර්ණ තෝරන්න."},
            {"n": 2, "box": [331, 478, 1379, 63], "pos": "left", "label_en": "Save received files to", "desc_en": "Displays the current destination folder where verified downloads are saved.", "label_si": "Save received files to", "desc_si": "ලැබෙන files ස්වයංක්‍රීයව save වන folder මාර්ගය පෙන්වයි."},
            {"n": 3, "box": [1720, 478, 134, 63], "pos": "top", "label_en": "Change folder", "desc_en": "Pick any writable local folder as your new receive destination.", "label_si": "Change folder", "desc_si": "ලැබෙන files save වීමට වෙනත් folder එකක් තෝරා ගැනීමට භාවිතා කරයි."},
            {"n": 4, "box": [331, 562, 236, 35], "pos": "right", "label_en": "Advanced connection details", "desc_en": "Expand to see this PC's local IP address, listening port, and device SHA-256 fingerprint for Pair by address.", "label_si": "Advanced connection details", "desc_si": "Pair by address සඳහා අවශ්‍ය local IP ලිපිනය, port අංකය සහ device fingerprint මෙහි ඇත."},
            {"n": 5, "box": [1762, 739, 92, 50], "pos": "left", "label_en": "Trusted devices → Remove", "desc_en": "Revokes trust from a saved peer so it can no longer connect without re-pairing.", "label_si": "Trusted devices → Remove", "desc_si": "Save කර ඇති device එකක විශ්වාසය ඉවත් කරයි (නැවත pair කළ යුතු වේ)."},
            {"n": 6, "box": [1718, 64, 162, 68], "pos": "left", "label_en": "Received files", "desc_en": "Quickly opens the configured destination folder from the top bar.", "label_si": "Received files", "desc_si": "ඉහළ තීරුවෙන්ම ලැබුණු files ඇති folder එක විවෘත කරයි."},
        ],
    },

    # ---------------- LINUX (16) ----------------
    ("linux", "send-empty"): {
        "title_en": "Linux Send screen",
        "title_si": "Linux Send තිරය",
        "alt_en": "Linux Glide Send page showing navigation sidebar, Add files..., Add folder..., Quick share with QR, Paired device, Connect, Settings, and Ready to receive banner.",
        "alt_si": "Linux Glide හි Send පිටුව: Add files..., Add folder..., Quick share with QR, Paired device, Connect, Settings සහ Ready to receive පෙන්වයි.",
        "callouts": [
            {"n": 1, "box": [12, 137, 187, 180], "pos": "right", "label_en": "Navigation sidebar", "desc_en": "Switch between Send, Receive, Activity, and Pair devices.", "label_si": "Navigation sidebar", "desc_si": "Send, Receive, Activity සහ Pair devices පිටු අතර මාරු වීමට."},
            {"n": 2, "box": [507, 300, 108, 40], "pos": "left", "label_en": "Add files...", "desc_en": "Open the Linux file picker to queue files for sending.", "label_si": "Add files...", "desc_si": "යැවීමට අවශ්‍ය files තෝරාගැනීමට Linux ගොනු කවුළුව විවෘත කරයි."},
            {"n": 3, "box": [627, 300, 110, 40], "pos": "right", "label_en": "Add folder...", "desc_en": "Queue a complete directory tree while keeping subfolder layout.", "label_si": "Add folder...", "desc_si": "සම්පූර්ණ folder එකක් එහි ඇතුළත structure එක සමඟම යැවීමට එක් කරයි."},
            {"n": 4, "box": [265, 455, 353, 38], "pos": "left", "label_en": "Quick share with QR", "desc_en": "Generate a one-time QR code for an unpaired phone to scan and receive.", "label_si": "Quick share with QR", "desc_si": "Pair නොකළ phone එකකින් එක් වරක් scan කර files ගැනීමට තාවකාලික QR එකක් සාදයි."},
            {"n": 5, "box": [626, 455, 353, 38], "pos": "top", "label_en": "Paired device card", "desc_en": "Send directly to a saved peer without scanning a new QR code.", "label_si": "Paired device කොටස", "desc_si": "නැවත QR scan නොකර save කර ඇති device එකකට කෙලින්ම යවයි."},
            {"n": 6, "box": [888, 528, 76, 30], "pos": "left", "label_en": "Connect", "desc_en": "Initiates direct TLS connection to the chosen paired device.", "label_si": "Connect", "desc_si": "තෝරාගත් paired device එකට කෙලින්ම ආරක්ෂිත සම්බන්ධතාවය අරඹයි."},
            {"n": 7, "box": [12, 563, 187, 42], "pos": "right", "label_en": "Settings", "desc_en": "Configure theme, Device Name, Download Directory, port, and diagnostics.", "label_si": "Settings", "desc_si": "Theme, Device Name, Download Directory, port සහ diagnostic logs පරීක්ෂා කරයි."},
            {"n": 8, "box": [12, 615, 187, 89], "pos": "right", "label_en": "Ready to receive status", "desc_en": "Shows whether this Linux desktop is currently listening for incoming offers.", "label_si": "Ready to receive තත්ත්වය", "desc_si": "මෙම Linux පරිගණකය දැනට files ලබාගැනීමට සූදානම්ද යන්න මෙහි දැක්වේ."},
        ],
    },
    ("linux", "send-staged"): {
        "title_en": "Linux Send screen with queued files",
        "title_si": "Linux Send තිරය (යැවීමට files තෝරාගත් පසු)",
        "alt_en": "Linux Send page with queued files, summary counter, Add files..., Add folder..., Clear all, individual remove X button, and Connect.",
        "alt_si": "Linux Send තිරයේ files එකතු කළ පසු: Clear all, එක් එක් file ඉවත් කරන X බොත්තම සහ Connect පෙන්වයි.",
        "callouts": [
            {"n": 1, "box": [259, 154, 214, 42], "pos": "right", "label_en": "Queued summary", "desc_en": "Displays item count and total batch size against the 1,000-item / 256 GiB limits.", "label_si": "තෝරාගත් ගණන සහ ප්‍රමාණය", "desc_si": "යැවීමට තැබූ files ගණන සහ මුළු ප්‍රමාණය (උපරිම 1,000 items / 256 GiB) පෙන්වයි."},
            {"n": 2, "box": [265, 202, 100, 40], "pos": "left", "label_en": "Add files...", "desc_en": "Appends more files to the existing batch.", "label_si": "Add files...", "desc_si": "දැනට තෝරාගත් ලැයිස්තුවට තව files එකතු කරයි."},
            {"n": 3, "box": [373, 202, 110, 40], "pos": "bottom", "label_en": "Add folder...", "desc_en": "Appends an entire folder to the batch.", "label_si": "Add folder...", "desc_si": "තවත් folder එකක් ලැයිස්තුවට එක් කරයි."},
            {"n": 4, "box": [491, 202, 84, 40], "pos": "right", "label_en": "Clear all", "desc_en": "Clears every staged item from the queue at once (Windows calls this Clear list).", "label_si": "Clear all", "desc_si": "ලැයිස්තුවේ ඇති සියලුම files එකවර ඉවත් කරයි (Windows හි Clear list ලෙස නම් කර ඇත)."},
            {"n": 5, "box": [940, 275, 28, 28], "pos": "right", "label_en": "Remove item (✕)", "desc_en": "Removes just that single item from the send queue (Windows shows Remove).", "label_si": "ඉවත් කරන්න (✕)", "desc_si": "ලැයිස්තුවෙන් එම තනි file එක පමණක් ඉවත් කරයි (Windows හි Remove ලෙස පෙනේ)."},
            {"n": 6, "box": [888, 484, 76, 30], "pos": "left", "label_en": "Connect", "desc_en": "Connects to the saved peer and delivers the transfer offer.", "label_si": "Connect", "desc_si": "Pair කළ device එකට සම්බන්ධ වී transfer එක ආරම්භ කරයි."},
        ],
    },
    ("linux", "send-qr-dialog"): {
        "title_en": "Linux Quick share with QR dialog",
        "title_si": "Linux Quick share with QR කවුළුව",
        "alt_en": "Linux Quick Share modal displaying QR code, expandable Connection details, Refresh code, and Cancel quick share.",
        "alt_si": "Linux Quick Share කවුළුව: QR code එක, Connection details, Refresh code සහ Cancel quick share පෙන්වයි.",
        "callouts": [
            {"n": 1, "box": [398, 236, 244, 244], "pos": "left", "label_en": "One-time send QR", "desc_en": "Receiver scans this with Glide Mobile (Receive files → Scan PC QR code). Then Linux shows Confirm Recipient before sending.", "label_si": "යැවීමේ QR code එක", "desc_si": "Phone එකෙන් Receive files → Scan PC QR code මඟින් මෙය scan කළ පසු, Linux හි Confirm Recipient තහවුරු කළ යුතුය."},
            {"n": 2, "box": [322, 494, 396, 48], "pos": "left", "label_en": "Connection details", "desc_en": "Expand to inspect local LAN IP endpoints and invitation details.", "label_si": "Connection details", "desc_si": "දෝෂ ඇති වුවහොත් local IP ලිපිනය සහ port විස්තර මෙයින් බලාගත හැක."},
            {"n": 3, "box": [375, 562, 118, 40], "pos": "left", "label_en": "Refresh code", "desc_en": "Issues a new one-time QR invitation token.", "label_si": "Refresh code", "desc_si": "පෙර code එක කල් ඉකුත් වූවා නම් අලුත් තාවකාලික QR code එකක් සාදයි."},
            {"n": 4, "box": [503, 563, 162, 38], "pos": "right", "label_en": "Cancel quick share", "desc_en": "Closes the QR dialog without clearing your staged files (Windows calls this Cancel QR share).", "label_si": "Cancel quick share", "desc_si": "Files ඉවත් නොකර QR share කවුළුව වසයි (Windows හි Cancel QR share වේ)."},
        ],
    },
    ("linux", "receive-ready"): {
        "title_en": "Linux Receive screen (listening active)",
        "title_si": "Linux Receive තිරය (ලැබීමට සූදානම්)",
        "alt_en": "Linux Receive page showing Turn off receiving, Connect a phone QR, Show larger code, Copy receive link, and Refresh code.",
        "alt_si": "Linux Receive පිටුව: Turn off receiving, phone එක සම්බන්ධ කිරීමේ QR එක, Show larger code, Copy receive link සහ Refresh code පෙන්වයි.",
        "callouts": [
            {"n": 1, "box": [265, 215, 156, 40], "pos": "right", "label_en": "Turn on receiving / Turn off receiving", "desc_en": "Toggles listening on Linux (unlike Windows, which says Start/Stop receiving).", "label_si": "Turn on receiving / Turn off receiving", "desc_si": "Linux හි files භාරගැනීම සක්‍රිය/අක්‍රිය කරයි (Windows හි Start/Stop receiving වෙනුවට මෙය භාවිතා වේ)."},
            {"n": 2, "box": [265, 373, 200, 200], "pos": "left", "label_en": "Connect a phone transfer QR", "desc_en": "Scan with Glide Mobile → Send files to send straight to this Linux desktop.", "label_si": "Phone එක සම්බන්ධ කරන QR", "desc_si": "Phone එකේ Send files තුළින් මෙය scan කර Linux PC එකට කෙලින්ම files එවන්න."},
            {"n": 3, "box": [485, 405, 192, 40], "pos": "right", "label_en": "Show larger code", "desc_en": "Opens the Scan to Connect zoomed QR dialog.", "label_si": "Show larger code", "desc_si": "Scan කිරීම පහසු වන ලෙස QR code එක විශාල කර පෙන්වයි."},
            {"n": 4, "box": [485, 453, 192, 40], "pos": "right", "label_en": "Copy receive link", "desc_en": "Copies the temporary glide:// receive URI to paste into Android.", "label_si": "Copy receive link", "desc_si": "තාවකාලික glide:// ලිපිනය copy කර අනෙක් device එකේ paste කිරීමට ලබා දෙයි."},
            {"n": 5, "box": [485, 501, 192, 40], "pos": "right", "label_en": "Refresh code", "desc_en": "Rotates to a fresh one-time QR invitation.", "label_si": "Refresh code", "desc_si": "අලුත් තාවකාලික QR code එකක් සාදයි."},
        ],
    },
    ("linux", "receive-offer"): {
        "title_en": "Linux incoming transfer offer prompt",
        "title_si": "Linux වෙත ලැබෙන transfer අනුමත කිරීමේ තිරය",
        "alt_en": "Linux Incoming transfer modal displaying peer identity summary, Decline, and Accept Transfer.",
        "alt_si": "Linux Incoming transfer කවුළුව: යවන්නාගේ විස්තර, Decline සහ Accept Transfer බොත්තම් පෙන්වයි.",
        "callouts": [
            {"n": 1, "box": [316, 268, 408, 150], "pos": "left", "label_en": "Sender & file summary", "desc_en": "Check the sender name, file count, and size before approving.", "label_si": "යවන්නා සහ files විස්තරය", "desc_si": "Files ලබා ගැනීමට පෙර යවන්නා සහ ප්‍රමාණය නිවැරදිදැයි පරීක්ෂා කරන්න."},
            {"n": 2, "box": [322, 440, 192, 38], "pos": "left", "label_en": "Decline", "desc_en": "Rejects the offer immediately without saving anything.", "label_si": "Decline", "desc_si": "Transfer එක ප්‍රතික්ෂේප කරයි."},
            {"n": 3, "box": [526, 439, 192, 40], "pos": "right", "label_en": "Accept Transfer", "desc_en": "Receives into private staging, checks SHA-256, and moves into your Download Directory.", "label_si": "Accept Transfer", "desc_si": "අනුමත කළ පසු files ආරක්ෂිතව ලැබී, SHA-256 පරීක්ෂා කර Download Directory වෙත save වේ."},
        ],
    },
    ("linux", "pair-find"): {
        "title_en": "Linux Pair devices — Find devices view",
        "title_si": "Linux Pair devices — Devices සෙවීම",
        "alt_en": "Linux Pair devices page showing Find devices, Show my QR and code, Enter pairing link, Pair by address, discovered peer row, and Remove.",
        "alt_si": "Linux Pair devices පිටුව: Find devices, Show my QR and code, Enter pairing link, Pair by address සහ Remove පෙන්වයි.",
        "callouts": [
            {"n": 1, "box": [265, 189, 122, 40], "pos": "left", "label_en": "Find devices", "desc_en": "Searches your local Wi-Fi or Ethernet LAN for desktop peers showing a pairing code.", "label_si": "Find devices", "desc_si": "Local ජාලයේ pair වීමට සූදානම් අනෙක් පරිගණක සොයයි."},
            {"n": 2, "box": [395, 189, 180, 40], "pos": "bottom", "label_en": "Show my QR and code", "desc_en": "Shows this Linux computer's pair-only QR code and 6-digit PIN.", "label_si": "Show my QR and code", "desc_si": "මෙම Linux පරිගණකයේ pair-only QR code එක සහ ඉලක්කම් 6 කේතය පෙන්වයි."},
            {"n": 3, "box": [583, 189, 141, 40], "pos": "top", "label_en": "Enter pairing link", "desc_en": "Accepts a pasted glide://pair URI from the peer device.", "label_si": "Enter pairing link", "desc_si": "අනෙක් device එකෙන් copy කළ pairing link එක paste කිරීමට භාවිතා කරයි."},
            {"n": 4, "box": [732, 189, 133, 40], "pos": "right", "label_en": "Pair by address", "desc_en": "Fallback pairing using IP:port + 6-digit PIN if discovery fails.", "label_si": "Pair by address", "desc_si": "ස්වයංක්‍රීය සෙවීම අසාර්ථක නම් IP:port සහ කේතය යොදා pair වේ."},
            {"n": 5, "box": [265, 277, 714, 46], "pos": "left", "label_en": "Discovered device row", "desc_en": "Click to proceed to the 6-digit pairing PIN prompt.", "label_si": "හමු වූ device එක", "desc_si": "මත click කර ඉලක්කම් 6 කේතය ඇතුළු කිරීමට යන්න."},
            {"n": 6, "box": [896, 415, 83, 40], "pos": "left", "label_en": "Remove", "desc_en": "Revokes trust for the listed saved peer.", "label_si": "Remove", "desc_si": "Save කර ඇති device එකේ විශ්වාසය ඉවත් කරයි."},
        ],
    },
    ("linux", "pair-show-qr"): {
        "title_en": "Linux Pair devices — Show my QR and code",
        "title_si": "Linux Pair devices — Pair-only QR සහ කේතය",
        "alt_en": "Linux Show my QR and code tab displaying pair-only QR, 6-digit code, Copy link, New code, Cancel, and Connection details.",
        "alt_si": "Linux Show my QR and code පිටුව: pair-only QR, ඉලක්කම් 6 කේතය, Copy link, New code, Cancel සහ Connection details පෙන්වයි.",
        "callouts": [
            {"n": 1, "box": [265, 281, 260, 260], "pos": "left", "label_en": "Pair-only QR", "desc_en": "Used exclusively for saving trust (scan with Android Pair device). Never sends files directly.", "label_si": "Pair-only QR code", "desc_si": "විශ්වාසය (trust) save කිරීමට පමණක් භාවිතා වේ; කෙලින්ම files යවන්නේ නැත."},
            {"n": 2, "box": [537, 358, 203, 118], "pos": "right", "label_en": "6-digit code", "desc_en": "Enter these digits on the other desktop under Find devices or Pair by address.", "label_si": "ඉලක්කම් 6 කේතය", "desc_si": "අනෙක් පරිගණකයේ Find devices හෝ Pair by address තුළ මෙම ඉලක්කම් 6 type කරන්න."},
            {"n": 3, "box": [265, 551, 91, 40], "pos": "left", "label_en": "Copy link", "desc_en": "Copies the temporary glide://pair invitation URI.", "label_si": "Copy link", "desc_si": "තාවකාලික pairing link එක copy කරයි."},
            {"n": 4, "box": [364, 551, 98, 40], "pos": "bottom-right-tight", "label_en": "New code", "desc_en": "Generates a fresh code and QR if the old one expired.", "label_si": "New code", "desc_si": "කේතය කල් ඉකුත් වුවහොත් අලුත් කේතයක් සාදයි."},
            {"n": 5, "box": [470, 551, 76, 40], "pos": "right", "label_en": "Cancel", "desc_en": "Closes the pair-only QR advertisement.", "label_si": "Cancel", "desc_si": "Pair වීමේ ආරාධනාව නවතයි."},
            {"n": 6, "box": [265, 609, 714, 48], "pos": "left", "label_en": "Connection details", "desc_en": "Expand to see this Linux PC's local IP address and port for Pair by address.", "label_si": "Connection details", "desc_si": "Pair by address සඳහා අවශ්‍ය local IP ලිපිනය සහ port අංකය මෙහි ඇත."},
        ],
    },
    ("linux", "pair-enter-code"): {
        "title_en": "Linux pairing — 6-digit code entry",
        "title_si": "Linux pairing — ඉලක්කම් 6 කේතය ඇතුළු කිරීම",
        "alt_en": "Linux pairing code entry form with 6-digit box, Pair, and Cancel buttons.",
        "alt_si": "Linux pairing කේතය ඇතුළු කරන කොටුව, Pair සහ Cancel බොත්තම් පෙන්වන තිරය.",
        "callouts": [
            {"n": 1, "box": [265, 307, 180, 40], "pos": "right", "label_en": "6-digit code box", "desc_en": "Type the 6-digit PIN currently shown on the peer computer.", "label_si": "කේත කොටුව", "desc_si": "අනෙක් පරිගණකයේ පෙන්වන ඉලක්කම් 6 කේතය මෙහි type කරන්න."},
            {"n": 2, "box": [265, 357, 63, 40], "pos": "left", "label_en": "Pair", "desc_en": "Begins verification so both computers approve and save trust.", "label_si": "Pair", "desc_si": "දෙපසම අනුමැතිය ලබා ගෙන pair වීම අරඹයි."},
            {"n": 3, "box": [336, 357, 76, 40], "pos": "right", "label_en": "Cancel", "desc_en": "Returns to Find devices without pairing.", "label_si": "Cancel", "desc_si": "Pair නොවී ආපසු සෙවීමට යයි."},
        ],
    },
    ("linux", "pair-complete"): {
        "title_en": "Linux pairing complete",
        "title_si": "Linux pairing සාර්ථකව අවසන් වීම",
        "alt_en": "Linux Pair devices screen showing Paired confirmation, Done button, and the new entry under Paired devices.",
        "alt_si": "Linux Pair devices තිරයේ සාර්ථකව pair වූ පණිවිඩය, Done බොත්තම සහ Paired devices ලැයිස්තුව පෙන්වයි.",
        "callouts": [
            {"n": 1, "box": [259, 248, 146, 24], "pos": "right", "label_en": "Pairing confirmed", "desc_en": "Both devices now trust each other's verified public key fingerprint.", "label_si": "Pair වීම සම්පූර්ණයි", "desc_si": "Device දෙකම එකිනෙකාගේ identity එක ස්ථිරවම හඳුනාගෙන ඇත."},
            {"n": 2, "box": [265, 278, 65, 40], "pos": "right", "label_en": "Done", "desc_en": "Clears the completion banner so you can start sending.", "label_si": "Done", "desc_si": "පණිවිඩය වසා ඉදිරියට යාමට සූදානම් කරයි."},
            {"n": 3, "box": [261, 416, 720, 44], "pos": "left", "label_en": "Paired devices entry", "desc_en": "Shows the newly added device, which is also selectable under Send → Paired device.", "label_si": "Paired devices ලැයිස්තුව", "desc_si": "මෙහි එක් වූ device එකට Send → Paired device මඟින් කෙලින්ම files යැවිය හැක."},
        ],
    },
    ("linux", "pair-not-found"): {
        "title_en": "Linux pairing — no devices found (recovery actions)",
        "title_si": "Linux pairing — Devices හමු නොවූ විට ගත හැකි පියවර",
        "alt_en": "Linux Pair devices notice showing Try again, Pair by address, and Open Settings recovery buttons.",
        "alt_si": "Linux Pair devices හි device හමු නොවූ විට පෙන්වන Try again, Pair by address සහ Open Settings බොත්තම්.",
        "callouts": [
            {"n": 1, "box": [265, 277, 714, 46], "pos": "left", "label_en": "Discovery notice", "desc_en": "Explains why no pairing peer appeared on the local network yet.", "label_si": "හමු නොවීමේ දැන්වීම", "desc_si": "අනෙක් device එක තවම සොයා ගැනීමට නොහැකි වූ හේතුව මෙහි දක්වයි."},
            {"n": 2, "box": [265, 333, 99, 40], "pos": "left", "label_en": "Try again", "desc_en": "Re-scans mDNS once the other desktop is on Show my QR and code.", "label_si": "Try again", "desc_si": "අනෙක් පරිගණකයේ Show my QR and code විවෘත කළ පසු නැවත සොයා බලයි."},
            {"n": 3, "box": [372, 333, 133, 40], "pos": "bottom", "label_en": "Pair by address", "desc_en": "Connects directly using IP:port and 6-digit PIN if discovery is blocked.", "label_si": "Pair by address", "desc_si": "මධ්‍ය ජාලය mDNS අවහිර කර ඇත්නම් IP:port සහ කේතය මඟින් සම්බන්ධ වේ."},
            {"n": 4, "box": [513, 333, 125, 40], "pos": "right", "label_en": "Open Settings", "desc_en": "Checks your Listening Port or network details in Settings.", "label_si": "Open Settings", "desc_si": "Settings වෙත ගොස් Listening Port සහ ජාල සැකසුම් පරීක්ෂා කරයි."},
        ],
    },
    ("linux", "pair-enter-link"): {
        "title_en": "Linux pairing — Enter pairing link",
        "title_si": "Linux pairing — Pairing link එක ඇතුළු කිරීම",
        "alt_en": "Linux Enter pairing link tab showing invitation text field, Pair, and Cancel buttons.",
        "alt_si": "Linux Enter pairing link පිටුව: link එක paste කරන කොටුව, Pair සහ Cancel පෙන්වයි.",
        "callouts": [
            {"n": 1, "box": [265, 281, 714, 40], "pos": "left", "label_en": "Pairing link field", "desc_en": "Paste a current glide://pair link copied from another device.", "label_si": "Pairing link කොටුව", "desc_si": "අනෙක් device එකෙන් copy කළ වලංගු glide://pair link එක මෙහි paste කරන්න."},
            {"n": 2, "box": [265, 331, 63, 40], "pos": "left", "label_en": "Pair", "desc_en": "Connects using the link and verifies the one-time secret.", "label_si": "Pair", "desc_si": "ලිපිනය ඔස්සේ සම්බන්ධ වී pair වීම ආරම්භ කරයි."},
            {"n": 3, "box": [336, 331, 76, 40], "pos": "right", "label_en": "Cancel", "desc_en": "Cancels link entry.", "label_si": "Cancel", "desc_si": "අවලංගු කර ආපසු යයි."},
        ],
    },
    ("linux", "pair-by-address"): {
        "title_en": "Linux pairing — Pair by address",
        "title_si": "Linux pairing — IP ලිපිනය මඟින් pair වීම",
        "alt_en": "Linux Pair by address form with IP:port input, 6-digit code input, Pair, and Cancel.",
        "alt_si": "Linux Pair by address තිරය: IP:port කොටුව, ඉලක්කම් 6 කේතය, Pair සහ Cancel පෙන්වයි.",
        "callouts": [
            {"n": 1, "box": [265, 281, 153, 40], "pos": "right", "label_en": "Address and port", "desc_en": "Type the peer's local IP address and port (for example 192.168.1.25:port).", "label_si": "IP ලිපිනය සහ port", "desc_si": "අනෙක් පරිගණකයේ IP ලිපිනය සහ Listening Port අංකය මෙහි ඇතුළු කරන්න."},
            {"n": 2, "box": [265, 361, 320, 40], "pos": "right", "label_en": "6-digit pairing code", "desc_en": "Enter the 6-digit PIN from Show my QR and code on that peer.", "label_si": "ඉලක්කම් 6 කේතය", "desc_si": "අනෙක් පරිගණකයේ පෙන්වන ඉලක්කම් 6 කේතය මෙහි ලියන්න."},
            {"n": 3, "box": [265, 411, 63, 40], "pos": "left", "label_en": "Pair", "desc_en": "Initiates direct LAN pairing.", "label_si": "Pair", "desc_si": "කෙලින්ම LAN ජාලය හරහා pair වීම අරඹයි."},
            {"n": 4, "box": [336, 411, 76, 40], "pos": "right", "label_en": "Cancel", "desc_en": "Leaves manual pairing.", "label_si": "Cancel", "desc_si": "අවලංගු කරයි."},
        ],
    },
    ("linux", "activity-sending"): {
        "title_en": "Linux Activity — sending in progress",
        "title_si": "Linux Activity — Files යැවෙමින් පවතින අවස්ථාව",
        "alt_en": "Linux Activity page showing live outbound transfer card, progress bar, speed, and Cancel transfer button.",
        "alt_si": "Linux Activity තිරයේ files යැවෙන ප්‍රගති කාඩ්පත සහ Cancel transfer බොත්තම පෙන්වයි.",
        "callouts": [
            {"n": 1, "box": [244, 136, 588, 148], "pos": "left", "label_en": "Live transfer progress card", "desc_en": "Displays active recipient, bytes transferred, transfer speed, and elapsed status.", "label_si": "සජීවී ප්‍රගති කාඩ්පත", "desc_si": "ලබන device එක, යැවුණු ප්‍රමාණය සහ වේගය පෙන්වයි."},
            {"n": 2, "box": [841, 217, 137, 38], "pos": "bottom", "label_en": "Cancel transfer", "desc_en": "Stops sending immediately before completion.", "label_si": "Cancel transfer", "desc_si": "Transfer එක සම්පූර්ණ වීමට පෙර නවතා දමයි."},
        ],
    },
    ("linux", "activity-history"): {
        "title_en": "Linux Activity — verified history",
        "title_si": "Linux Activity — සම්පූර්ණ වූ transfers ඉතිහාසය",
        "alt_en": "Linux Activity page showing Received files, Clear history, Search history, All/Sent/Received filters, and Completed status.",
        "alt_si": "Linux Activity පිටුව: Received files, Clear history, Search history, All/Sent/Received සහ Completed පෙන්වයි.",
        "callouts": [
            {"n": 1, "box": [755, 70, 122, 40], "pos": "left", "label_en": "Received files", "desc_en": "Opens your Linux Download Directory in the system file manager.", "label_si": "Received files", "desc_si": "ලැබුණු files save වී ඇති folder එක Linux file manager තුළ විවෘත කරයි."},
            {"n": 2, "box": [885, 70, 115, 40], "pos": "top", "label_en": "Clear history", "desc_en": "Clears history entries from the screen while keeping your downloaded files intact.", "label_si": "Clear history", "desc_si": "Save වූ files මකා නොදමා, පැරණි වාර්තා ලැයිස්තුව පමණක් මකා දමයි."},
            {"n": 3, "box": [592, 157, 170, 40], "pos": "left", "label_en": "Search history", "desc_en": "Filters entries by device name or filename.", "label_si": "Search history", "desc_si": "Device නම හෝ file නම අනුව පැරණි transfers සොයයි."},
            {"n": 4, "box": [770, 158, 209, 38], "pos": "top", "label_en": "All / Sent / Received", "desc_en": "Shows all entries, sent batches only, or received batches only.", "label_si": "All / Sent / Received", "desc_si": "සියලුම, යැවූ හෝ ලැබුණු වාර්තා වෙන වෙනම පෙරා බලයි."},
            {"n": 5, "box": [883, 229, 84, 23], "pos": "right", "label_en": "Completed", "desc_en": "Confirms every file passed SHA-256 verification and committed safely.", "label_si": "Completed", "desc_si": "සෑම file එකක්ම SHA-256 පරීක්ෂාවෙන් සාර්ථකව සම්පූර්ණ වූ බව පෙන්වයි."},
        ],
    },
    ("linux", "settings-upper"): {
        "title_en": "Linux Settings (appearance, name, folder, trust)",
        "title_si": "Linux Settings — ඉහළ කොටස (වර්ණ, නම සහ folder)",
        "alt_en": "Linux Settings page upper section with Appearance theme radio options, Device Name, Download Directory, Browse..., Trusted Devices Details, and Revoke button.",
        "alt_si": "Linux Settings ඉහළ කොටස: Appearance, Device Name, Download Directory, Browse... සහ Trusted Devices Details පෙන්වයි.",
        "callouts": [
            {"n": 1, "box": [265, 170, 258, 38], "pos": "right", "label_en": "Appearance", "desc_en": "Select Follow System, Light, or Dark theme.", "label_si": "Appearance", "desc_si": "Follow System, Light හෝ Dark වර්ණය තෝරන්න."},
            {"n": 2, "box": [265, 354, 1207, 40], "pos": "top", "label_en": "Device Name", "desc_en": "Sets the name nearby Windows, Linux, and Android devices see.", "label_si": "Device Name", "desc_si": "අනෙක් devices වලට මෙම පරිගණකය පෙනෙන නම වෙනස් කරයි."},
            {"n": 3, "box": [265, 454, 1106, 40], "pos": "top", "label_en": "Download Directory", "desc_en": "Shows the folder where verified incoming files are committed.", "label_si": "Download Directory", "desc_si": "ලැබෙන files ස්වයංක්‍රීයව save වන folder මාර්ගය පෙන්වයි."},
            {"n": 4, "box": [1381, 454, 91, 40], "pos": "right", "label_en": "Browse...", "desc_en": "Chooses a different destination directory on Linux.", "label_si": "Browse...", "desc_si": "වෙනත් destination folder එකක් තෝරාගැනීමට භාවිතා කරයි."},
            {"n": 5, "box": [289, 665, 1159, 48], "pos": "top", "label_en": "Trusted Devices Details", "desc_en": "Expand any paired peer to inspect its verified identity fingerprint.", "label_si": "Trusted Devices Details", "desc_si": "Pair කළ device එකක fingerprint විස්තර බලාගැනීමට මෙය විවෘත කරන්න."},
            {"n": 6, "box": [265, 749, 245, 38], "pos": "right", "label_en": "Revoke selected trusted device", "desc_en": "Removes the selected peer from trusted storage.", "label_si": "Revoke selected trusted device", "desc_si": "තෝරාගත් device එකේ විශ්වාසය (trust) ඉවත් කරයි."},
        ],
    },
    ("linux", "settings-lower"): {
        "title_en": "Linux Settings — lower section (port, logs, About)",
        "title_si": "Linux Settings — පහළ කොටස (Port, Logs සහ About)",
        "alt_en": "Linux Settings page lower section showing Listening Port, Export diagnostic logs, and Glide for Linux version card.",
        "alt_si": "Linux Settings පහළ කොටස: Listening Port, Export diagnostic logs සහ Glide for Linux තොරතුරු පෙන්වයි.",
        "callouts": [
            {"n": 1, "box": [265, 547, 140, 40], "pos": "right", "label_en": "Listening Port", "desc_en": "Shows the fixed or active local TCP port needed if using Pair by address.", "label_si": "Listening Port", "desc_si": "Pair by address භාවිතයේදී අවශ්‍ය වන local TCP port අංකය පෙන්වයි."},
            {"n": 2, "box": [265, 645, 178, 40], "pos": "right", "label_en": "Export diagnostic logs", "desc_en": "Saves offline logs so you can inspect connection errors safely.", "label_si": "Export diagnostic logs", "desc_si": "ගැටලුවක් ඇති වුවහොත් පරීක්ෂා කිරීම සඳහා offline diagnostic logs file එකක් save කරයි."},
            {"n": 3, "box": [244, 730, 1249, 114], "pos": "top", "label_en": "Glide for Linux (About)", "desc_en": "Shows installed Glide version, local device details, and offline status.", "label_si": "Glide for Linux (About)", "desc_si": "ස්ථාපිත Glide අනුවාදය සහ තොරතුරු දක්වයි."},
        ],
    },

    # ---------------- ANDROID (17) ----------------
    ("android", "home"): {
        "title_en": "Android Home screen",
        "title_si": "Android Home (මුල්) තිරය",
        "alt_en": "Android Glide Home screen with Send files, Receive files, Pair device button, Paired devices list with Last seen, and Home/History/Settings bottom tabs.",
        "alt_si": "Android Glide මුල් තිරය: Send files, Receive files, Pair device, Paired devices ලැයිස්තුව සහ පහළ tabs පෙන්වයි.",
        "callouts": [
            {"n": 1, "box": [60, 394, 960, 170], "pos": "top", "label_en": "Send files", "desc_en": "Pick photos, files, or folders, then pick a nearby phone or scan a desktop QR.", "label_si": "Send files", "desc_si": "Photos, files හෝ folders තෝරා, ළඟ phone එකකට හෝ PC QR එකක් scan කර යවන්න."},
            {"n": 2, "box": [60, 611, 960, 168], "pos": "bottom", "label_en": "Receive files", "desc_en": "Makes your phone discoverable and lets you open Scan PC QR code or background receiving.", "label_si": "Receive files", "desc_si": "Phone එකට files ලැබීමට සූදානම් කිරීම හෝ PC QR code එක scan කිරීම අරඹයි."},
            {"n": 3, "box": [755, 886, 248, 72], "pos": "left", "label_en": "Pair device", "desc_en": "Scan a desktop pair-only QR code (or link) to save trusted identity for future sends.", "label_si": "Pair device", "desc_si": "PC එකේ Pair devices හි QR එක scan කර දීර්ඝකාලීන විශ්වාසය save කරගනී."},
            {"n": 4, "box": [60, 1031, 960, 210], "pos": "bottom", "label_en": "Paired devices (Paired / Last seen)", "desc_en": "Lists already paired devices with their Paired badge or Last seen timestamp. Saved peers don't need fresh QR pairing next time.", "label_si": "Paired devices (Paired / Last seen)", "desc_si": "දැනටමත් pair කර ඇති devices සහ අවසන් වරට හමු වූ වේලාව (Last seen) මෙහි පෙන්වයි."},
            {"n": 5, "box": [60, 2138, 960, 172], "pos": "top", "label_en": "Bottom tabs (Home / History / Settings)", "desc_en": "Switch between main actions, verified transfer history, and phone preferences.", "label_si": "පහළ මෙනුව (Home / History / Settings)", "desc_si": "ප්‍රධාන තිරය, පැරණි transfers ඉතිහාසය සහ Settings අතර මාරු වීමට."},
        ],
    },
    ("android", "send-select-empty"): {
        "title_en": "Android file selection (empty)",
        "title_si": "Android files තෝරන තිරය (හිස් අවස්ථාව)",
        "alt_en": "Android Select files screen showing Back button, Choose photos & videos, Choose files & documents, and Choose folder.",
        "alt_si": "Android හි files තෝරන තිරය: Back, Choose photos & videos, Choose files & documents සහ Choose folder පෙන්වයි.",
        "callouts": [
            {"n": 1, "box": [60, 155, 238, 144], "pos": "right", "label_en": "Back", "desc_en": "Returns to Home without sending.", "label_si": "Back", "desc_si": "Files නොයවා ආපසු Home වෙත යයි."},
            {"n": 2, "box": [132, 566, 816, 168], "pos": "top", "label_en": "Choose photos & videos", "desc_en": "Opens Android's system media picker without needing broad storage access.", "label_si": "Choose photos & videos", "desc_si": "Photos සහ videos පහසුවෙන් තේරීමට Android gallery picker විවෘත කරයි."},
            {"n": 3, "box": [132, 782, 816, 144], "pos": "right", "label_en": "Choose files & documents", "desc_en": "Opens Android's system file picker to choose one or many files.", "label_si": "Choose files & documents", "desc_si": "Documents, APKs, PDF හෝ වෙනත් ඕනෑම file එකක් තේරීමට."},
            {"n": 4, "box": [132, 974, 816, 144], "pos": "bottom", "label_en": "Choose folder", "desc_en": "Selects an entire folder (up to 10,000 inner files / 16 depth levels, 1,000 batch items).", "label_si": "Choose folder", "desc_si": "සම්පූර්ණ folder එකක්ම එකවර තෝරා ගැනීමට භාවිතා කරයි."},
        ],
    },
    ("android", "send-select-staged"): {
        "title_en": "Android file selection (staged items)",
        "title_si": "Android files තෝරාගත් පසු (Staged)",
        "alt_en": "Android staged files screen with Clear all, batch summary, Remove per item, Proceed to send, and Add more buttons.",
        "alt_si": "Android files තෝරාගත් තිරය: Clear all, Remove, Proceed to send සහ තවත් files එකතු කරන බොත්තම් පෙන්වයි.",
        "callouts": [
            {"n": 1, "box": [818, 196, 164, 58], "pos": "left", "label_en": "Clear all", "desc_en": "Empties the staged send list without touching your saved files on Android.", "label_si": "Clear all", "desc_si": "Phone එකේ files delete නොකර, යැවීමට තැබූ ලැයිස්තුව පමණක් හිස් කරයි."},
            {"n": 2, "box": [59, 493, 962, 194], "pos": "top", "label_en": "Staged batch summary", "desc_en": "Shows item count and total size before picking a receiver.", "label_si": "තෝරාගත් විස්තරය", "desc_si": "යැවීමට සූදානම් files ගණන සහ මුළු ප්‍රමාණය මෙහි පෙන්වයි."},
            {"n": 3, "box": [795, 815, 154, 52], "pos": "top", "label_en": "Remove", "desc_en": "Removes just that item from the batch.", "label_si": "Remove", "desc_si": "එම තනි file එක පමණක් ලැයිස්තුවෙන් ඉවත් කරයි."},
            {"n": 4, "box": [60, 1789, 960, 170], "pos": "top", "label_en": "Proceed to send", "desc_en": "Advances to Select recipient (nearby phones or QR scan for Windows/Linux).", "label_si": "Proceed to send", "desc_si": "ලබන device එක තේරීමේ තිරයට (හෝ QR scan කිරීමට) ඉදිරියට යයි."},
            {"n": 5, "box": [60, 1982, 648, 312], "pos": "right", "label_en": "Add more photos / files / folder", "desc_en": "Keep adding extra items before proceeding.", "label_si": "තව files එකතු කරන්න", "desc_si": "Proceed එබීමට පෙර තව photos, files හෝ folders මෙයටම එක් කළ හැක."},
        ],
    },
    ("android", "recipient-list"): {
        "title_en": "Android Select recipient screen (Light theme)",
        "title_si": "Android ලබන්නා තෝරන තිරය (Select recipient)",
        "alt_en": "Android Select recipient screen in Light theme showing Scanning indicator, nearby discovered device card, and Scan QR code or paste invite option.",
        "alt_si": "Android ලබන්නා තෝරන තිරය (Light theme): Scanning තත්ත්වය, ළඟ හමු වූ device එක සහ Scan QR code or paste invite පෙන්වයි.",
        "callouts": [
            {"n": 1, "box": [757, 386, 251, 84], "pos": "top", "label_en": "Scanning", "desc_en": "Shows Nearby discovery is actively looking for receiving Android devices.", "label_si": "Scanning", "desc_si": "අසල ඇති අනෙක් phones ස්වයංක්‍රීයව සොයමින් සිටින බව දක්වයි."},
            {"n": 2, "box": [60, 554, 960, 211], "pos": "bottom", "label_en": "Discovered device card", "desc_en": "Tap the receiver's phone name here for phone-to-phone transfer (opens 4-digit check next).", "label_si": "හමු වූ phone එක", "desc_si": "වෙනත් phone එකකට යැවීමට එහි නම මත tap කරන්න (ඉන්පසු ඉලක්කම් 4 තහවුරු කිරීම පැමිණේ)."},
            {"n": 3, "box": [60, 830, 960, 192], "pos": "bottom", "label_en": "Scan QR code or paste invite", "desc_en": "Tap here when sending to Windows or Linux: scan their Receive QR or paste the receive link.", "label_si": "Scan QR code or paste invite", "desc_si": "Windows හෝ Linux PC එකකට යැවීමේදී Receive QR එක scan කිරීමට හෝ link එක paste කිරීමට මෙය tap කරන්න."},
        ],
    },
    ("android", "send-qr-confirmation"): {
        "title_en": "Android QR / invitation confirmation dialog",
        "title_si": "Android QR ආරාධනාව තහවුරු කිරීමේ කවුළුව",
        "alt_en": "Android confirmation modal after scanning desktop QR or pasting link showing Cancel, Connect, and Paste invitation button underneath.",
        "alt_si": "PC QR එක scan කළ පසු Android හි පෙනෙන කවුළුව: Cancel, Connect සහ Paste invitation බොත්තම පෙන්වයි.",
        "callouts": [
            {"n": 1, "box": [88, 845, 904, 510], "pos": "top", "label_en": "Invitation summary dialog", "desc_en": "Verify the target desktop device name and fingerprint snippet before connecting.", "label_si": "සම්බන්ධ වන PC විස්තරය", "desc_si": "සම්බන්ධ වීමට පෙර PC එකේ නම සහ fingerprint කොටස නිවැරදිදැයි බලන්න."},
            {"n": 2, "box": [436, 1417, 164, 146], "pos": "left", "label_en": "Cancel", "desc_en": "Declines connecting to that QR invitation.", "label_si": "Cancel", "desc_si": "මෙම QR ආරාධනාව අවලංගු කර ආපසු යයි."},
            {"n": 3, "box": [647, 1417, 302, 146], "pos": "bottom", "label_en": "Connect", "desc_en": "Connects to the desktop over encrypted TLS and waits for desktop acceptance.", "label_si": "Connect", "desc_si": "PC එකට ආරක්ෂිතව සම්බන්ධ වී transfer අනුමැතිය ඉල්ලයි."},
            {"n": 4, "box": [145, 2127, 790, 150], "pos": "top", "label_en": "Paste invitation", "desc_en": "Camera-free alternative: paste a glide:// link copied from Windows or Linux.", "label_si": "Paste invitation", "desc_si": "Camera අවසර නොදී PC එකෙන් copy කළ glide:// link එක paste කර සම්බන්ධ වීමට."},
        ],
    },
    ("android", "send-progress"): {
        "title_en": "Android sending progress screen",
        "title_si": "Android files යැවෙන ප්‍රගති තිරය",
        "alt_en": "Android transfer screen while sending showing Back button, item progress percentage/speed, and Cancel transfer button.",
        "alt_si": "Android files යැවෙමින් පවතින තිරය: Back, ප්‍රගතිය, වේගය සහ Cancel transfer බොත්තම පෙන්වයි.",
        "callouts": [
            {"n": 1, "box": [60, 155, 238, 144], "pos": "right", "label_en": "Back", "desc_en": "Minimizes/navigates without cancelling an active transfer if foreground notification is active.", "label_si": "Back", "desc_si": "Transfer එක නොනවත්වා ආපසු යා හැක (සක්‍රිය transfer අවසන් වන තුරු ඉදිරියට යයි)."},
            {"n": 2, "box": [118, 825, 844, 414], "pos": "top", "label_en": "Item & overall progress", "desc_en": "Shows current filename, percentage complete, and live transfer speed.", "label_si": "ප්‍රගතිය සහ වේගය", "desc_si": "යැවෙන file නම, ප්‍රතිශතය සහ දැනට වේගය පෙන්වයි."},
            {"n": 3, "box": [131, 1309, 818, 146], "pos": "bottom", "label_en": "Cancel transfer", "desc_en": "Aborts the transfer immediately; unverified partial items are cleaned up on the receiver.", "label_si": "Cancel transfer", "desc_si": "Files යැවීම වහාම නවතයි; අසම්පූර්ණ files ලබන්නාගේ folder එකට save නොවේ."},
        ],
    },
    ("android", "nearby-verify"): {
        "title_en": "Android Nearby 4-digit verification (Dark theme)",
        "title_si": "Android Nearby ඉලක්කම් 4 තහවුරු කිරීම (Dark theme)",
        "alt_en": "Android Verify connection screen in Dark theme displaying 4 verification digits, Numbers match, and Don't match buttons.",
        "alt_si": "Android Verify connection තිරය (Dark theme): ඉලක්කම් 4 කේතය, Numbers match සහ Don't match බොත්තම් පෙන්වයි.",
        "callouts": [
            {"n": 1, "box": [131, 1139, 818, 176], "pos": "left", "label_en": "4-digit verification code", "desc_en": "Read and compare these 4 digits out loud with the other phone. Both must show the same digits.", "label_si": "ඉලක්කම් 4 තහවුරු කේතය", "desc_si": "Phone දෙකේම මෙම ඉලක්කම් 4 සමානදැයි ඇසින් හෝ වචනයෙන් සසඳා බලන්න."},
            {"n": 2, "box": [131, 1565, 818, 170], "pos": "left", "label_en": "Numbers match", "desc_en": "Tap only when both phones display identical digits. This authorizes the phone-to-phone session.", "label_si": "Numbers match", "desc_si": "Phone දෙකේම අංක සමාන නම් පමණක් මෙය tap කරන්න (මෙය desktop ඉලක්කම් 6 කේතයට වෙනස්ය)."},
            {"n": 3, "box": [132, 1764, 816, 156], "pos": "bottom", "label_en": "Don't match", "desc_en": "Tap immediately to reject and disconnect if the 4 digits differ.", "label_si": "Don't match", "desc_si": "අංක වෙනස් නම් කිසිසේත් ඉදිරියට නොගොස් මෙයින් සම්බන්ධතාවය ප්‍රතික්ෂේප කරන්න."},
        ],
    },
    ("android", "nearby-verify-light"): {
        "title_en": "Android Nearby 4-digit verification (Light theme)",
        "title_si": "Android Nearby ඉලක්කම් 4 තහවුරු කිරීම (Light theme උදාහරණය)",
        "alt_en": "Android Verify connection screen in Light theme showing 4 verification digits, Numbers match, and Don't match.",
        "alt_si": "Android Verify connection තිරය (Light theme): ඉලක්කම් 4, Numbers match සහ Don't match බොත්තම් පෙන්වයි.",
        "callouts": [
            {"n": 1, "box": [64, 570, 411, 90], "pos": "left", "label_en": "Light-theme 4-digit display", "desc_en": "Glide adapts automatically to Light mode while keeping identical security verification controls.", "label_si": "Light theme හි ඉලක්කම් 4", "desc_si": "Light theme හිදීත් ආරක්ෂාව සඳහා එකම ආකාරයේ ඉලක්කම් 4 සංසන්දනය පෙන්වයි."},
            {"n": 2, "box": [64, 783, 412, 88], "pos": "left", "label_en": "Numbers match", "desc_en": "Confirms both Android devices match before revealing any file offer.", "label_si": "Numbers match", "desc_si": "Phone දෙකේම අංක එක සමාන නම් තහවුරු කිරීමට tap කරන්න."},
            {"n": 3, "box": [66, 883, 408, 79], "pos": "bottom", "label_en": "Don't match", "desc_en": "Rejects mismatched verification digits safely.", "label_si": "Don't match", "desc_si": "අංක නොගැලපේ නම් වහාම ප්‍රතික්ෂේප කරයි."},
        ],
    },
    ("android", "receive-offer"): {
        "title_en": "Android incoming file offer prompt",
        "title_si": "Android වෙත ලැබෙන file අනුමත කිරීමේ තිරය",
        "alt_en": "Android incoming transfer prompt showing sender and file preview, Accept file, and Decline buttons.",
        "alt_si": "Android හි ලැබෙන file පෙන්වන තිරය: file විස්තරය, Accept file සහ Decline බොත්තම් පෙන්වයි.",
        "callouts": [
            {"n": 1, "box": [132, 887, 816, 204], "pos": "top", "label_en": "Sender & file preview", "desc_en": "Check who is sending, the filename/count, and total size before accepting.", "label_si": "යවන්නා සහ file විස්තරය", "desc_si": "Files භාරගැනීමට පෙර එවන්නේ කවුද, ගණන සහ ප්‍රමාණය කොපමණදැයි පරීක්ෂා කරන්න."},
            {"n": 2, "box": [131, 1138, 818, 170], "pos": "left", "label_en": "Accept file", "desc_en": "Explicit consent required before saving; shows 'Accept N files' for multi-file batches.", "label_si": "Accept file", "desc_si": "ඔබ මෙය tap කළ පසු පමණක් transfer එක ඇරඹේ (files කිහිපයකදී 'Accept N files' ලෙස පෙනේ)."},
            {"n": 3, "box": [132, 1337, 816, 150], "pos": "bottom", "label_en": "Decline", "desc_en": "Refuses the transfer immediately.", "label_si": "Decline", "desc_si": "අනවශ්‍ය file එකක් නම් මෙයින් ප්‍රතික්ෂේප කරන්න."},
        ],
    },
    ("android", "receive-complete"): {
        "title_en": "Android verified receive completion",
        "title_si": "Android file ලැබීම සාර්ථකව අවසන් වීම",
        "alt_en": "Android Transfer complete screen displaying Verified badge, Share file, Open file, and Done buttons.",
        "alt_si": "Android Transfer complete තිරය: Verified සලකුණ, Share file, Open file සහ Done බොත්තම් පෙන්වයි.",
        "callouts": [
            {"n": 1, "box": [686, 1292, 227, 74], "pos": "right", "label_en": "Verified badge", "desc_en": "Confirms Glide checked the file's SHA-256 checksum byte-for-byte before saving to Download/Glide.", "label_si": "Verified සලකුණ", "desc_si": "File එක කිසිදු හානියකින් තොරව (SHA-256 පරීක්ෂාවෙන්) සාර්ථකව Download/Glide වෙත save වූ බව තහවුරු කරයි."},
            {"n": 2, "box": [132, 1642, 816, 144], "pos": "top", "label_en": "Share file", "desc_en": "Opens the Android share sheet for the verified received file.", "label_si": "Share file", "desc_si": "ලැබුණු file එක phone එකේ වෙනත් යෙදුමකට share කිරීමට."},
            {"n": 3, "box": [132, 1816, 816, 144], "pos": "left", "label_en": "Open file", "desc_en": "Opens the saved file immediately with a compatible Android app.", "label_si": "Open file", "desc_si": "ලැබුණු file එක කෙලින්ම phone එකේ ඇති සුදුසු app එකකින් විවෘත කර බලයි."},
            {"n": 4, "box": [132, 1990, 816, 144], "pos": "bottom", "label_en": "Done", "desc_en": "Closes the completion summary and returns home.", "label_si": "Done", "desc_si": "ප්‍රතිඵල තිරය වසා මුල් තිරයට යයි."},
        ],
    },
    ("android", "history"): {
        "title_en": "Android Transfer history screen",
        "title_si": "Android Transfer history (ඉතිහාස) තිරය",
        "alt_en": "Android History tab showing trash clear icon, All/Sent/Received filter chips, and verified session history card.",
        "alt_si": "Android History පිටුව: ඉතිහාසය මකන icon එක, All/Sent/Received පෙරහන් සහ පැරණි transfer කාඩ්පත පෙන්වයි.",
        "callouts": [
            {"n": 1, "box": [956, 169, 80, 80], "pos": "left", "label_en": "Clear history", "desc_en": "Clears history log records only; received files remain untouched in Download/Glide.", "label_si": "Clear history icon", "desc_si": "Save වූ files මකා නොදමා History ලැයිස්තුව පමණක් පිරිසිදු කරයි."},
            {"n": 2, "box": [218, 323, 644, 144], "pos": "right", "label_en": "All / Sent / Received", "desc_en": "Filters history entries by direction.", "label_si": "All / Sent / Received", "desc_si": "සියලුම transfers, යැවූ files හෝ ලැබුණු files පමණක් වෙන්කර බැලීමට."},
            {"n": 3, "box": [60, 515, 960, 282], "pos": "bottom", "label_en": "Verified session card", "desc_en": "Tap any session card to open its items, verification status, or Open/Share actions.", "label_si": "Transfer ඉතිහාස කාඩ්පත", "desc_si": "මෙය මත tap කර එදා යැවූ හෝ ලැබුණු files නැවත විවෘත කිරීමට හෝ share කිරීමට හැක."},
        ],
    },
    ("android", "settings-appearance"): {
        "title_en": "Android Settings — Device name & appearance",
        "title_si": "Android Settings — Device නම සහ පෙනුම",
        "alt_en": "Android Settings screen upper section showing Edit device name, Theme mode selector, Dynamic color switch, and Reduced motion toggle.",
        "alt_si": "Android Settings ඉහළ කොටස: Edit, Theme mode, Dynamic color සහ Reduced motion සැකසුම් පෙන්වයි.",
        "callouts": [
            {"n": 1, "box": [683, 476, 289, 120], "pos": "left", "label_en": "Edit (Device name)", "desc_en": "Renames how this Android phone appears to Windows, Linux, and other phones.", "label_si": "Edit (Device name)", "desc_si": "අනෙක් devices වලට මෙම phone එක පෙනෙන නම වෙනස් කරයි."},
            {"n": 2, "box": [97, 866, 411, 396], "pos": "right", "label_en": "Theme mode", "desc_en": "Choose System default, Light, or Dark appearance.", "label_si": "Theme mode", "desc_si": "System, Light හෝ Dark වර්ණ මාදිලිය තෝරන්න."},
            {"n": 3, "box": [816, 1346, 156, 96], "pos": "left", "label_en": "Dynamic color", "desc_en": "Uses Android Material You wallpaper colors on supported devices.", "label_si": "Dynamic color", "desc_si": "ඔබේ phone wallpaper එකේ වර්ණ අනුව theme එක සකසයි."},
            {"n": 4, "box": [97, 1511, 800, 492], "pos": "right", "label_en": "Reduced motion", "desc_en": "Minimizes UI animations for calmer accessibility and lower visual distraction.", "label_si": "Reduced motion", "desc_si": "තිරයේ චලනයන් සහ animations අඩු කර පහසුවෙන් කියවීමට සකසයි."},
        ],
    },
    ("android", "settings-trust"): {
        "title_en": "Android Settings — Notifications & Trusted devices",
        "title_si": "Android Settings — Notifications සහ Trusted devices",
        "alt_en": "Android Settings middle section showing Notification details, Keep screen awake, Manage trusted devices, Pair with another device, and Delete all.",
        "alt_si": "Android Settings මැද කොටස: Notification details, Keep screen awake, Manage trusted devices, Pair with another device සහ Delete all පෙන්වයි.",
        "callouts": [
            {"n": 1, "box": [816, 468, 156, 96], "pos": "top", "label_en": "Notification details", "desc_en": "Controls whether background transfer notifications show filenames and peer details.", "label_si": "Notification details", "desc_si": "Notification එකේ file නම් පෙන්වන්නේද යන්න පාලනය කරයි."},
            {"n": 2, "box": [816, 669, 156, 96], "pos": "bottom", "label_en": "Keep screen awake", "desc_en": "Prevents the phone screen from sleeping during long transfers.", "label_si": "Keep screen awake", "desc_si": "විශාල files යවන අතරතුර screen එක off වීම වළක්වයි."},
            {"n": 3, "box": [108, 1195, 864, 144], "pos": "left", "label_en": "Manage trusted devices", "desc_en": "View saved desktop fingerprints and revoke trust for any single device.", "label_si": "Manage trusted devices", "desc_si": "Pair කළ computers බැලීමට සහ අවශ්‍ය නම් ඉවත් කිරීමට."},
            {"n": 4, "box": [108, 1375, 864, 144], "pos": "bottom", "label_en": "Pair with another device", "desc_en": "Opens QR pairing scanner to save trust with a desktop.", "label_si": "Pair with another device", "desc_si": "අලුත් PC එකක් සමඟ pair වීමට QR scanner විවෘත කරයි."},
            {"n": 5, "box": [580, 1792, 392, 120], "pos": "left", "label_en": "Delete all", "desc_en": "Clears all Glide transfer history and temporary/saved data on request.", "label_si": "Delete all", "desc_si": "Transfer ඉතිහාසය සහ යෙදුම් දත්ත මකා දමයි (තහවුරු කිරීමක් අසයි)."},
        ],
    },
    ("android", "settings-storage"): {
        "title_en": "Android Settings — Save location, diagnostics, & About",
        "title_si": "Android Settings — Save ස්ථානය, Diagnostics සහ About",
        "alt_en": "Android Settings lower section showing Download/Glide default location, Choose folder, Nearby diagnostics, Privacy policy, and Open source licenses.",
        "alt_si": "Android Settings පහළ කොටස: Download/Glide ස්ථානය, Choose folder, Nearby diagnostics, Privacy policy සහ Open source licenses පෙන්වයි.",
        "callouts": [
            {"n": 1, "box": [53, 447, 359, 59], "pos": "right", "label_en": "Download/Glide (default)", "desc_en": "Default save location for all verified files received on Android.", "label_si": "Download/Glide (පෙරනිමි ස්ථානය)", "desc_si": "සාමාන්‍යයෙන් ලැබෙන සියලුම files save වන Android folder එක මෙයයි."},
            {"n": 2, "box": [60, 673, 409, 120], "pos": "right", "label_en": "Choose folder", "desc_en": "Select a custom folder (afterwards, Use Download/Glide resets back to default).", "label_si": "Choose folder", "desc_si": "වෙනත් folder එකක් තේරීමට (පසුව Use Download/Glide මඟින් මුල් ස්ථානයට ගත හැක)."},
            {"n": 3, "box": [108, 1018, 864, 144], "pos": "bottom", "label_en": "Nearby diagnostics", "desc_en": "Checks radio and permission readiness when nearby phones fail to appear.", "label_si": "Nearby diagnostics", "desc_si": "අසල devices නොපෙනෙන විට Bluetooth/Wi-Fi සහ අවසර තත්ත්වය පරීක්ෂා කරයි."},
            {"n": 4, "box": [108, 1584, 864, 144], "pos": "left", "label_en": "Privacy policy", "desc_en": "Confirms Glide operates 100% offline with zero telemetry or cloud storage.", "label_si": "Privacy policy", "desc_si": "Glide අන්තර්ජාලය හෝ cloud භාවිතා නොකරන බව දක්වන පෞද්ගලිකත්ව ප්‍රතිපත්තිය."},
            {"n": 5, "box": [108, 1764, 864, 144], "pos": "bottom", "label_en": "Open source licenses", "desc_en": "Lists third-party libraries bundled inside the offline app.", "label_si": "Open source licenses", "desc_si": "යෙදුම තුළ ඇති විවෘත කේත බලපත්‍ර විස්තර."},
        ],
    },
    ("android", "recipient-bluetooth-off"): {
        "title_en": "Android recovery — Bluetooth is turned off",
        "title_si": "Android දෝෂ නිවැරදි කිරීම — Bluetooth අක්‍රියයි",
        "alt_en": "Android discovery blocked screen because Bluetooth is off, displaying Open Bluetooth settings and Check again.",
        "alt_si": "Bluetooth off නිසා Android discovery නැවතුණු තිරය: Open Bluetooth settings සහ Check again බොත්තම් පෙන්වයි.",
        "callouts": [
            {"n": 1, "box": [132, 1142, 816, 144], "pos": "top", "label_en": "Open Bluetooth settings", "desc_en": "Jumps straight to Android Bluetooth settings so you can switch Bluetooth on.", "label_si": "Open Bluetooth settings", "desc_si": "Bluetooth on කිරීම සඳහා කෙලින්ම Android Bluetooth settings විවෘත කරයි."},
            {"n": 2, "box": [132, 1322, 816, 144], "pos": "bottom", "label_en": "Check again", "desc_en": "Re-evaluates radio readiness and resumes device discovery when you return.", "label_si": "Check again", "desc_si": "Bluetooth on කර ආපසු Glide වෙත පැමිණි පසු නැවත සෙවීම ආරම්භ කරයි."},
        ],
    },
    ("android", "recipient-wifi-off"): {
        "title_en": "Android recovery — Wi-Fi is turned off",
        "title_si": "Android දෝෂ නිවැරදි කිරීම — Wi-Fi අක්‍රියයි",
        "alt_en": "Android discovery blocked screen because Wi-Fi is off, displaying Open Wi-Fi settings and Check again.",
        "alt_si": "Wi-Fi off නිසා Android discovery නැවතුණු තිරය: Open Wi-Fi settings සහ Check again බොත්තම් පෙන්වයි.",
        "callouts": [
            {"n": 1, "box": [132, 1142, 816, 144], "pos": "top", "label_en": "Open Wi-Fi settings", "desc_en": "Opens Android Wi-Fi settings so you can join the same local Wi-Fi as the peer.", "label_si": "Open Wi-Fi settings", "desc_si": "අනෙක් device එක ඇති Wi-Fi ජාලයටම සම්බන්ධ වීමට Wi-Fi settings විවෘත කරයි."},
            {"n": 2, "box": [132, 1322, 816, 144], "pos": "bottom", "label_en": "Check again", "desc_en": "Re-checks Wi-Fi connection and continues searching for receivers.", "label_si": "Check again", "desc_si": "Wi-Fi සම්බන්ධ කළ පසු නැවත devices සෙවීම අරඹයි."},
        ],
    },
    ("android", "recipient-no-devices"): {
        "title_en": "Android recovery — No nearby devices found",
        "title_si": "Android දෝෂ නිවැරදි කිරීම — ළඟ devices කිසිවක් හමු නොවීය",
        "alt_en": "Android discovery notice when no devices appear, offering Cancel and Retry buttons.",
        "alt_si": "ළඟ devices හමු නොවූ විට Android හි පෙනෙන Cancel සහ Retry බොත්තම්.",
        "callouts": [
            {"n": 1, "box": [132, 1202, 390, 144], "pos": "top", "label_en": "Cancel", "desc_en": "Returns to file selection; to send to a PC instead, choose Scan QR code or paste invite.", "label_si": "Cancel", "desc_si": "සෙවීම නවතා ආපසු යයි (PC එකකට යැවීමට නම් Scan QR code or paste invite භාවිතා කරන්න)."},
            {"n": 2, "box": [558, 1202, 390, 144], "pos": "top", "label_en": "Retry", "desc_en": "Searches again once the other Android phone opens Receive files on the same network.", "label_si": "Retry", "desc_si": "අනෙක් phone එකේ Receive files විවෘත කළ පසු නැවත සොයා බලයි."},
        ],
    },
}


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def has_sinhala(text: str) -> bool:
    return bool(SINHALA_RE.search(text))


def _load_bold_font(size: int) -> ImageFont.ImageFont | ImageFont.FreeTypeFont:
    candidates = [
        r"C:\Windows\Fonts\segoeuib.ttf",
        r"C:\Windows\Fonts\NotoSans-Bold.ttf",
        r"C:\Windows\Fonts\arialbd.ttf",
        "/usr/share/fonts/truetype/noto/NotoSans-Bold.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    ]
    for fp in candidates:
        if Path(fp).exists():
            try:
                return ImageFont.truetype(fp, size)
            except OSError:
                pass
    return ImageFont.load_default()


def _rects_overlap(a: tuple[int, int, int, int], b: tuple[int, int, int, int]) -> bool:
    return not (a[2] <= b[0] or a[0] >= b[2] or a[3] <= b[1] or a[1] >= b[3])


def _circle_intersects_rect(cx: int, cy: int, r: int, rect: tuple[int, int, int, int]) -> bool:
    nearest_x = max(rect[0], min(cx, rect[2]))
    nearest_y = max(rect[1], min(cy, rect[3]))
    return (cx - nearest_x) ** 2 + (cy - nearest_y) ** 2 < r * r


def _place_badge(
    box: list[int],
    pos_hint: str,
    img_w: int,
    img_h: int,
    badge_d: int,
    placed_centers: list[tuple[int, int]],
    all_boxes: list[tuple[int, int, int, int]],
) -> tuple[int, int]:
    x, y, w, h = box
    r = badge_d // 2
    gap = 6

    cand_map: dict[str, tuple[int, int]] = {
        "left": (x - r - gap, y + h // 2),
        "right": (x + w + r + gap, y + h // 2),
        "top": (x + w // 2, y - r - gap),
        "bottom": (x + w // 2, y + h + r + gap),
        "top-left": (x - r // 2, y - r - gap),
        "top-right": (x + w + r // 2, y - r - gap),
        "bottom-left": (x - r // 2, y + h + r + gap),
        "bottom-right": (x + w + r // 2, y + h + r + gap),
        "bottom-tight": (x + w // 2, y + h + r + 1),
        "bottom-right-tight": (x + w, y + h + r + 1),
        "top-tight": (x + w // 2, y - r - 1),
        "in-tl": (x + r + 8, y + r + 8),
        "in-tr": (x + w - r - 8, y + r + 8),
    }

    order = [pos_hint] if pos_hint in cand_map else []
    for k in ["left", "top", "right", "bottom", "top-left", "top-right", "bottom-left", "bottom-right", "bottom-tight", "bottom-right-tight", "top-tight", "in-tl", "in-tr"]:
        if k not in order:
            order.append(k)

    best_pt: tuple[int, int] | None = None
    best_score = -10**9

    for idx, key in enumerate(order):
        cx, cy = cand_map[key]
        cx = max(r + 6, min(img_w - r - 6, cx))
        cy = max(r + 6, min(img_h - r - 6, cy))

        # Distance check against placed circles
        circle_hit = any(math.hypot(cx - ox, cy - oy) < (badge_d + 6) for ox, oy in placed_centers)
        # Overlap check against ALL highlight boxes (don't cover text inside target or sibling boxes)
        box_hits = sum(1 for bx in all_boxes if _circle_intersects_rect(cx, cy, r - 1, bx))

        score = -idx * 10
        if circle_hit:
            score -= 10000
        score -= box_hits * 500
        if not circle_hit and box_hits == 0:
            return (cx, cy)
        if score > best_score:
            best_score = score
            best_pt = (cx, cy)

    # Extra ring search around target box perimeter if needed
    assert best_pt is not None
    if any(math.hypot(best_pt[0] - ox, best_pt[1] - oy) < badge_d + 4 for ox, oy in placed_centers):
        for shift in range(badge_d, badge_d * 5, 16):
            for dx, dy in [(0, -shift), (0, shift), (-shift, 0), (shift, 0)]:
                tx = max(r + 6, min(img_w - r - 6, best_pt[0] + dx))
                ty = max(r + 6, min(img_h - r - 6, best_pt[1] + dy))
                if not any(math.hypot(tx - ox, ty - oy) < badge_d + 4 for ox, oy in placed_centers):
                    if not any(_circle_intersects_rect(tx, ty, r - 1, bx) for bx in all_boxes):
                        return (tx, ty)
        for shift in range(badge_d, badge_d * 6, 16):
            for dx, dy in [(0, -shift), (0, shift), (-shift, 0), (shift, 0)]:
                tx = max(r + 6, min(img_w - r - 6, best_pt[0] + dx))
                ty = max(r + 6, min(img_h - r - 6, best_pt[1] + dy))
                if not any(math.hypot(tx - ox, ty - oy) < badge_d + 4 for ox, oy in placed_centers):
                    return (tx, ty)

    return best_pt


def _draw_expired_stamp(
    im: Image.Image,
    draw: ImageDraw.ImageDraw,
    all_boxes: list[tuple[int, int, int, int]],
    placed_badges: list[tuple[int, int]],
    badge_d: int,
    light_mode: bool,
) -> None:
    w, h = im.size
    text = "EXAMPLE ONLY - THIS CODE HAS EXPIRED"
    font_size = 20 if w >= 1400 else (18 if w >= 900 else 13)
    font = _load_bold_font(font_size)
    tb = draw.textbbox((0, 0), text, font=font)
    tw, th = tb[2] - tb[0], tb[3] - tb[1]
    pad_x, pad_y = 14, 8
    pw, ph = tw + pad_x * 2, th + pad_y * 2
    margin = 16

    candidates = [
        (w - pw - margin, h - ph - margin),
        (margin, h - ph - margin),
        ((w - pw) // 2, h - ph - margin),
        (w - pw - margin, margin),
        ((w - pw) // 2, margin),
    ]
    r = badge_d // 2
    chosen = candidates[0]
    for px, py in candidates:
        rect = (px, py, px + pw, py + ph)
        if any(_rects_overlap(rect, bx) for bx in all_boxes):
            continue
        if any(_circle_intersects_rect(cx, cy, r + 4, rect) for cx, cy in placed_badges):
            continue
        chosen = (px, py)
        break

    px, py = chosen
    rect = (px, py, px + pw, py + ph)
    if light_mode:
        fill_col = (254, 243, 199, 245)
        border_col = (180, 83, 9, 255)
        text_col = (120, 53, 15, 255)
    else:
        fill_col = (120, 53, 15, 242)
        border_col = (245, 158, 11, 255)
        text_col = (254, 243, 199, 255)

    draw.rounded_rectangle(rect, radius=10, fill=fill_col, outline=border_col, width=3)
    draw.text(
        (px + pad_x - tb[0], py + pad_y - tb[1]),
        text,
        font=font,
        fill=text_col,
    )


def annotate_image(
    src_path: Path,
    dest_path: Path,
    platform_lower: str,
    slug: str,
    spec: dict[str, Any],
) -> None:
    """Create annotated PNG with rounded highlight boxes, outside circle badges, and expired QR stamp."""
    dest_path.parent.mkdir(parents=True, exist_ok=True)
    im = Image.open(src_path).convert("RGBA")
    w, h = im.size
    light_mode = (platform_lower, slug) in LIGHT_STATES

    # Enforced minimum circle diameters: >=36px desktop, >=48px on >=900px phone captures.
    if platform_lower == "android":
        badge_d = 52 if w >= 900 else 38
        stroke_w = 4 if w >= 900 else 3
    else:
        badge_d = 46 if w >= 1500 else 38
        stroke_w = 4 if w >= 1500 else 3

    box_color = (217, 119, 6, 255) if light_mode else (95, 134, 237, 255)       # #D97706 / #5F86ED
    badge_fill = (180, 83, 9, 255) if light_mode else (79, 121, 232, 255)
    halo_color = (17, 24, 39, 240) if light_mode else (8, 13, 28, 245)

    overlay = Image.new("RGBA", im.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(overlay)

    callouts = spec["callouts"]
    all_boxes: list[tuple[int, int, int, int]] = []
    for c in callouts:
        bx, by, bw, bh = c["box"]
        all_boxes.append((bx, by, bx + bw, by + bh))

    # 1. Draw rounded highlight boxes first
    for c in callouts:
        bx, by, bw, bh = c["box"]
        rad = min(12, max(4, bw // 6), max(4, bh // 6))
        draw.rounded_rectangle(
            [bx - 1, by - 1, bx + bw + 1, by + bh + 1],
            radius=rad + 1,
            outline=halo_color,
            width=stroke_w + 2,
        )
        draw.rounded_rectangle(
            [bx, by, bx + bw, by + bh],
            radius=rad,
            outline=box_color,
            width=stroke_w,
        )

    # 2. Place and draw numbered circle badges without overlapping labels or other badges
    placed_centers: list[tuple[int, int]] = []
    font_size = int(badge_d * 0.56)
    font = _load_bold_font(font_size)
    r = badge_d // 2

    for c in callouts:
        cx, cy = _place_badge(
            c["box"],
            c.get("pos", "left"),
            w,
            h,
            badge_d,
            placed_centers,
            all_boxes,
        )
        placed_centers.append((cx, cy))

        # Check non-overlap invariant
        for ox, oy in placed_centers[:-1]:
            if math.hypot(cx - ox, cy - oy) < badge_d * 0.85:
                raise RuntimeError(
                    f"Annotated badge overlap on {platform_lower}/{slug} callout #{c['n']}"
                )

        # Dark outer outline + filled badge circle
        draw.ellipse(
            [cx - r - 2, cy - r - 2, cx + r + 2, cy + r + 2],
            fill=halo_color,
        )
        draw.ellipse(
            [cx - r, cy - r, cx + r, cy + r],
            fill=badge_fill,
            outline=(255, 255, 255, 235),
            width=2,
        )

        label = str(c["n"])
        tb = draw.textbbox((0, 0), label, font=font)
        tw, th = tb[2] - tb[0], tb[3] - tb[1]
        tx = cx - tw / 2 - tb[0]
        ty = cy - th / 2 - tb[1]
        # Crisp dark text stroke then white bold digit
        draw.text(
            (tx, ty),
            label,
            font=font,
            fill=(255, 255, 255, 255),
            stroke_width=2,
            stroke_fill=halo_color,
        )

    # 3. Expired example warning stamp on QR/numeric-code screenshots
    if (platform_lower, slug) in EXPIRED_STAMP_STATES:
        _draw_expired_stamp(im, draw, all_boxes, placed_centers, badge_d, light_mode)

    combined = Image.alpha_composite(im, overlay).convert("RGB")
    combined.save(dest_path, format="PNG", optimize=True)


def prepare_assets(source_dir: Path | None = None) -> list[dict[str, Any]]:
    """Verify existing assets or import non-destructively from --source, then render annotated copies."""
    if source_dir is None:
        if not MANIFEST_PATH.exists():
            raise RuntimeError(
                f"Manifest missing at {MANIFEST_PATH}; pass --source PATH to initialize."
            )
        manifest: list[dict[str, Any]] = json.loads(
            MANIFEST_PATH.read_text(encoding="utf-8")
        )
        canonical_map: dict[str, dict[str, Any]] = {}
        # First pass: verify originals and render annotated files for unique captures
        for entry in manifest:
            pub_path = ROOT / entry["published"]
            if not pub_path.exists():
                raise RuntimeError(f"Published screenshot missing: {pub_path}")
            actual_sha = sha(pub_path)
            if actual_sha != entry["published_sha256"]:
                raise RuntimeError(
                    f"Original screenshot hash altered: {pub_path} ({actual_sha} != {entry['published_sha256']})"
                )
            if entry.get("duplicate_of") is None:
                plat = entry["platform"].lower()
                slug = entry["state"]
                spec = CALLOUT_SPECS.get((plat, slug))
                if spec is None:
                    raise RuntimeError(f"Missing CALLOUT_SPECS for {plat}/{slug}")
                ann_rel = Path("assets/annotated") / plat / f"{pub_path.stem}-annotated.png"
                ann_abs = ROOT / ann_rel
                annotate_image(pub_path, ann_abs, plat, slug, spec)
                entry["annotated"] = str(ann_rel).replace("\\", "/")
                entry["annotated_sha256"] = sha(ann_abs)
                entry["callouts"] = [
                    {
                        "n": c["n"],
                        "box": c["box"],
                        "label_en": c["label_en"],
                        "desc_en": c["desc_en"],
                        "label_si": c["label_si"],
                        "desc_si": c["desc_si"],
                    }
                    for c in spec["callouts"]
                ]
                canonical_map[pub_path.name] = entry

        # Second pass: link duplicate entries to canonical annotated copy
        for entry in manifest:
            if entry.get("duplicate_of") is not None:
                parent = canonical_map.get(entry["duplicate_of"])
                if parent is None:
                    raise RuntimeError(f"Broken duplicate_of link: {entry['duplicate_of']}")
                entry["annotated"] = parent["annotated"]
                entry["annotated_sha256"] = parent["annotated_sha256"]
                entry["callouts"] = parent["callouts"]

        MANIFEST_PATH.write_text(
            json.dumps(manifest, indent=2, ensure_ascii=False) + "\n",
            encoding="utf-8",
        )
        return manifest

    # Non-destructive import from --source PATH (never renames/unlinks anything in source_dir)
    if not source_dir.exists() or not source_dir.is_dir():
        raise RuntimeError(f"Source directory does not exist: {source_dir}")

    manifest = []
    for platform, slugs in SLUGS.items():
        pdir = source_dir / platform
        if not pdir.is_dir():
            raise RuntimeError(f"Expected platform folder not found in source: {pdir}")
        originals = sorted(
            [
                x
                for x in pdir.iterdir()
                if x.is_file()
                and x.suffix.lower() in {".png", ".jpg", ".jpeg"}
                and not x.stem.startswith(platform.lower() + "-")
            ]
        )
        if not originals:
            originals = sorted(
                [x for x in pdir.iterdir() if x.is_file() and x.suffix.lower() in {".png", ".jpg", ".jpeg"}]
            )
        if len(originals) != len(slugs):
            raise RuntimeError(f"{platform}: expected {len(slugs)} images, found {len(originals)} in {pdir}")

        out_dir = ASSETS / platform.lower()
        out_dir.mkdir(parents=True, exist_ok=True)
        seen_by_hash: dict[str, Path] = {}
        for source, slug in zip(originals, slugs):
            ext = ".png" if platform != "Android" else ".jpg"
            new_name = f"{platform.lower()}-{slug}-dark{ext}"
            im = Image.open(source)
            digest = sha(source)
            target = out_dir / new_name
            if digest in seen_by_hash:
                published = seen_by_hash[digest]
            else:
                shutil.copy2(source, target)
                published = target
                seen_by_hash[digest] = target
            plat_lower = platform.lower()
            spec = CALLOUT_SPECS.get((plat_lower, slug))
            ann_rel_str = None
            ann_sha = None
            callouts_meta = []
            if published == target and spec is not None:
                ann_rel = Path("assets/annotated") / plat_lower / f"{target.stem}-annotated.png"
                ann_abs = ROOT / ann_rel
                annotate_image(target, ann_abs, plat_lower, slug, spec)
                ann_rel_str = str(ann_rel).replace("\\", "/")
                ann_sha = sha(ann_abs)
                callouts_meta = [
                    {
                        "n": c["n"],
                        "box": c["box"],
                        "label_en": c["label_en"],
                        "desc_en": c["desc_en"],
                        "label_si": c["label_si"],
                        "desc_si": c["desc_si"],
                    }
                    for c in spec["callouts"]
                ]
            manifest.append(
                {
                    "platform": platform,
                    "original": source.name,
                    "renamed": new_name,
                    "state": slug,
                    "theme": "light"
                    if slug in {"recipient-list", "nearby-verify-light", "nearby-verify-light-duplicate", "recipient-list-duplicate"}
                    else "dark",
                    "dimensions": list(im.size),
                    "sha256": digest,
                    "published_sha256": sha(published),
                    "duplicate_of": None if published == target else published.name,
                    "redaction_regions": [],
                    "published": str(published.relative_to(ROOT)).replace("\\", "/"),
                    "annotated": ann_rel_str,
                    "annotated_sha256": ann_sha,
                    "callouts": callouts_meta,
                }
            )
    # Propagate annotated fields to duplicates and write manifest
    by_pub = {e["renamed"]: e for e in manifest if e["duplicate_of"] is None}
    for e in manifest:
        if e["duplicate_of"] is not None and e["duplicate_of"] in by_pub:
            parent = by_pub[e["duplicate_of"]]
            e["annotated"] = parent["annotated"]
            e["annotated_sha256"] = parent["annotated_sha256"]
            e["callouts"] = parent["callouts"]

    MANIFEST_PATH.write_text(
        json.dumps(manifest, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    return manifest


def _t(en: str, si_val: str, si: bool) -> str:
    if si:
        if not has_sinhala(si_val):
            raise ValueError(f"Sinhala string missing Sinhala characters: {si_val!r}")
        return si_val
    return en


def fig(
    platform: str,
    slug: str,
    si: bool,
    used_collector: set[tuple[str, str]] | None = None,
) -> str:
    key = (platform, slug)
    if key not in CALLOUT_SPECS:
        raise ValueError(f"Unknown figure spec: {key}")
    spec = CALLOUT_SPECS[key]
    ext = ".jpg" if platform == "android" else ".png"
    orig_stem = f"{platform}-{slug}-dark"
    ann_rel = Path("assets/annotated") / platform / f"{orig_stem}-annotated.png"
    orig_rel = Path("assets") / platform / f"{orig_stem}{ext}"
    if not (ROOT / orig_rel).exists():
        raise FileNotFoundError(f"Missing source figure asset: {ROOT / orig_rel}")
    if not (ROOT / ann_rel).exists():
        raise FileNotFoundError(f"Missing annotated figure asset: {ROOT / ann_rel}")

    if used_collector is not None:
        used_collector.add(key)

    title = _t(spec["title_en"], spec["title_si"], si)
    alt = _t(spec["alt_en"], spec["alt_si"], si)
    img_src = str(ann_rel).replace("\\", "/")
    kind_cls = "fig-phone" if platform == "android" else "fig-desktop"
    zoom_hint = "විශාල කර බැලීමට click කරන්න" if si else "Click to zoom"

    rows = []
    callouts = spec["callouts"]
    expected_n = list(range(1, len(callouts) + 1))
    actual_n = [c["n"] for c in callouts]
    if actual_n != expected_n:
        raise ValueError(f"Non-contiguous callouts on {key}: {actual_n}")

    for c in callouts:
        lbl = c["label_si"] if si else c["label_en"]
        desc = _t(c["desc_en"], c["desc_si"], si)
        rows.append(
            f'<tr><td class="c-num"><span class="badge-pill">{c["n"]}</span></td>'
            f'<td class="c-lbl"><code>{html.escape(lbl)}</code></td>'
            f'<td>{html.escape(desc)}</td></tr>'
        )

    th_no = "අංකය" if si else "No."
    th_ctrl = "Control / කොටස" if si else "Control"
    th_desc = "කාර්යය" if si else "What it does"
    table = (
        f'<table class="callout-tbl"><thead><tr>'
        f'<th>{th_no}</th><th>{th_ctrl}</th><th>{th_desc}</th>'
        f'</tr></thead><tbody>{"".join(rows)}</tbody></table>'
    )

    return (
        f'<figure class="{kind_cls}" data-asset="{html.escape(img_src)}">'
        f'<button type="button" class="zoom-trigger" data-caption="{html.escape(title)}" aria-label="{html.escape(title + " — " + zoom_hint)}">'
        f'<img src="{html.escape(img_src)}" alt="{html.escape(alt)}" loading="lazy">'
        f'<span class="zoom-hint" aria-hidden="true">{html.escape(zoom_hint)}</span>'
        f'</button>'
        f'<figcaption><strong>{html.escape(title)}</strong>{table}</figcaption>'
        f'</figure>'
    )


def section(i: int, title: str, body: str, si: bool) -> str:
    top_lbl = "↑ මුලට යන්න" if si else "↑ Back to top"
    return (
        f'<section id="s{i}">'
        f'<h2>{i}. {html.escape(title)}</h2>'
        f'{body}'
        f'<p class="back-top"><a href="#top">{html.escape(top_lbl)}</a></p>'
        f'</section>'
    )


ROUTES = [
    ("Windows", "Windows"),
    ("Windows", "Linux"),
    ("Windows", "Android"),
    ("Linux", "Linux"),
    ("Linux", "Windows"),
    ("Linux", "Android"),
    ("Android", "Android"),
    ("Android", "Windows"),
    ("Android", "Linux"),
]


def _desktop_recv_btn(platform: str) -> str:
    if platform == "Windows":
        return "Start receiving"
    return "Turn on receiving"


def _desktop_qr_confirm_step(platform: str) -> tuple[str, str]:
    if platform == "Windows":
        return (
            "On Windows, confirm <code>Confirm receiving device</code> → <code>Send files</code>.",
            "Windows හි <code>Confirm receiving device</code> පණිවිඩය තුළ <code>Send files</code> ඔබන්න.",
        )
    return (
        "On Linux, confirm <code>Confirm Recipient</code> in the confirmation modal.",
        "Linux හි <code>Confirm Recipient</code> කවුළුව තහවුරු කරන්න.",
    )


def route_card(a: str, b: str, si: bool) -> str:
    if si:
        heading = f"{a} සිට {b} දක්වා"
        actor = "යවන්නා"
        dest = "ලබන්නා"
        if a == "Android":
            select = "Home → <code>Send files</code> → <code>Choose files &amp; documents</code> හෝ <code>Choose folder</code> → <code>Proceed to send</code>."
        else:
            select = "<code>Send</code> → <code>Add files</code> / <code>Add folder</code> → <code>Paired device</code> තෝරන්න (නැතිනම් <code>Quick share with QR</code>)."

        if b == "Android":
            recv = "Glide විවෘතව තබන්න (<code>Home</code>, <code>History</code>, <code>Settings</code> හෝ <code>Receive files</code>). Offer එක පෙනුණු විට <code>Accept file</code> tap කරන්න."
        else:
            rbtn = _desktop_recv_btn(b)
            abtn = "Accept transfer" if b == "Windows" else "Accept Transfer"
            recv = f"<code>Receive</code> → <code>{rbtn}</code> ඔබන්න; offer එක පෙනුණු විට <code>{abtn}</code> ඔබන්න."

        if a == "Android" and b == "Android":
            connect = "<code>Select recipient</code> තුළ අනෙක් phone එක tap කරන්න; phone දෙකේම <code>Verify connection</code> ඉලක්කම් 4 සමාන නම් පමණක් <code>Numbers match</code> tap කරන්න."
        elif a == "Android":
            connect = f"{b} හි <code>Receive</code> තුළ ඇති transfer QR code එක phone එකේ <code>Scan QR code or paste invite</code> මඟින් scan කර (හෝ link එක paste කර) <code>Connect</code> tap කරන්න."
        elif b == "Android":
            _, qr_confirm_si = _desktop_qr_confirm_step(a)
            connect = f"<code>Paired device</code> හි save කළ phone එක තෝරා connect කරන්න (නැවත QR අවශ්‍ය නැත). Pair කර නැත්නම් <code>Quick share with QR</code> භාවිතා කර phone එකෙන් scan කළ පසු {qr_confirm_si}"
        else:
            connect = f"<code>Paired device</code> තුළ අනෙක් {b} PC එක තෝරා සම්බන්ධ කරන්න. පෙර pair කර නැතිනම් මුලින් දෙපසම <code>Pair devices</code> තුළ pair කරන්න."

        if b == "Android":
            finish = "<code>Transfer complete</code> හි <code>Verified</code> සලකුණ පරීක්ෂා කරන්න; file එක <code>Download/Glide</code> හෝ Settings හි තෝරාගත් folder එකට save වේ."
        else:
            finish = f"<code>Activity</code> හි <code>Completed</code> තත්ත්වය බලන්න; ලැබුණු files <code>Received files</code> හෝ <code>Open folder</code> මඟින් විවෘත කරන්න."
    else:
        heading = f"{a} → {b}"
        actor = "Sender"
        dest = "Receiver"
        if a == "Android":
            select = "<code>Home</code> → <code>Send files</code> → <code>Choose files &amp; documents</code> or <code>Choose folder</code> → <code>Proceed to send</code>."
        else:
            select = "<code>Send</code> → <code>Add files</code> / <code>Add folder</code> → <code>Paired device</code> (or <code>Quick share with QR</code> for a one-time phone send)."

        if b == "Android":
            recv = "Keep Glide open on <code>Home</code>, <code>History</code>, <code>Settings</code>, or <code>Receive files</code>. Tap <code>Accept file</code> when the offer arrives."
        else:
            rbtn = _desktop_recv_btn(b)
            abtn = "Accept transfer" if b == "Windows" else "Accept Transfer"
            recv = f"Open <code>Receive</code> → click <code>{rbtn}</code>; click <code>{abtn}</code> on the incoming transfer prompt."

        if a == "Android" and b == "Android":
            connect = "In <code>Select recipient</code>, tap the receiving phone; compare the 4 digits on both screens and tap <code>Numbers match</code> only if they match."
        elif a == "Android":
            connect = f"On {b}, show the transfer QR under <code>Receive</code>. On Android tap <code>Scan QR code or paste invite</code>, scan it (or paste the link), and tap <code>Connect</code>."
        elif b == "Android":
            qr_confirm_en, _ = _desktop_qr_confirm_step(a)
            connect = f"Pick the saved phone under <code>Paired device</code> (no QR rescan needed). For an unpaired phone, use <code>Quick share with QR</code>; after the phone scans it, {qr_confirm_en[0].lower() + qr_confirm_en[1:]}"
        else:
            connect = f"Pick the receiving {b} computer under <code>Paired device</code>. If it is not paired yet, pair both desktops once under <code>Pair devices</code>."

        if b == "Android":
            finish = "Confirm the <code>Verified</code> badge on completion; files are committed to <code>Download/Glide</code> or your chosen Settings folder."
        else:
            finish = "Confirm <code>Completed</code> in <code>Activity</code>; use <code>Received files</code> or <code>Open folder</code> to view them."

    return (
        f'<article class="route"><h3>{html.escape(heading)}</h3>'
        f'<ol><li><b>{actor}:</b> {select}</li>'
        f'<li><b>{dest}:</b> {recv}</li>'
        f'<li><b>{actor}:</b> {connect}</li>'
        f'<li><b>{dest}:</b> {finish}</li></ol></article>'
    )


def _table_html(headers: list[str], rows: list[list[str]], si: bool) -> str:
    if si:
        # Validate non-UI descriptive cells include Sinhala text
        for r in rows:
            last_cell = re.sub(r"<[^>]+>", "", r[-1])
            if last_cell.strip() and not has_sinhala(last_cell):
                raise ValueError(f"Sinhala table cell missing Sinhala characters: {last_cell!r}")
    ths = "".join(f"<th>{html.escape(h)}</th>" for h in headers)
    trs = "".join("<tr>" + "".join(f"<td>{cell}</td>" for cell in row) + "</tr>" for row in rows)
    return f'<div class="tbl-wrap"><table><thead><tr>{ths}</tr></thead><tbody>{trs}</tbody></table></div>'


def _section_1_quickstart(si: bool, used: set[tuple[str, str]]) -> str:
    if si:
        lead = (
            "<p><strong>Glide 1.0.0</strong> යනු අන්තර්ජාලය, cloud storage හෝ ගිණුම් කිසිවක් නොමැතිව "
            "එකම Wi-Fi / Ethernet ජාලය තුළ පවතින <strong>Windows, Ubuntu/Linux සහ Android</strong> උපාංග අතර "
            "ආරක්ෂිතව files හුවමාරු කරන මෘදුකාංගයකි. UI බොත්තම් සහ tab නම් යෙදුමේ පෙනෙන English ආකාරයෙන්ම දක්වා ඇත.</p>"
        )
        qs_a = """
        <div class="callout-card">
          <h3>ඉක්මන් ආරම්භය A: Android → Windows (පළමු වරට file එකක් යැවීම)</h3>
          <ol>
            <li><strong>එකම ජාලයට සම්බන්ධ වන්න:</strong> Android phone එක සහ Windows PC එක එකම Wi-Fi / LAN router එකට සම්බන්ධ කරන්න.</li>
            <li><strong>Windows හි <code>Start receiving</code> ඔබන්න:</strong> Windows Glide විවෘත කර වම් මෙනුවේ <code>Receive</code> → <code>Start receiving</code> click කරන්න.</li>
            <li><strong>Android හි files තෝරන්න:</strong> Phone එකේ Glide විවෘත කර <code>Send files</code> → <code>Choose photos &amp; videos</code> හෝ <code>Choose files &amp; documents</code> → <code>Proceed to send</code> tap කරන්න.</li>
            <li><strong>PC QR code එක scan කරන්න:</strong> Android හි <code>Scan QR code or paste invite</code> tap කර Windows තිරයේ පෙනෙන QR එක scan කර <code>Connect</code> ඔබන්න.</li>
            <li><strong>Windows හි <code>Accept transfer</code> ඔබන්න:</strong> PC තිරයේ එන <code>Incoming transfer</code> කවුළුවේ යවන්නා සහ file විස්තරය බලා <code>Accept transfer</code> click කරන්න.</li>
            <li><strong><code>Received files</code> මඟින් බලන්න:</strong> අවසානයේ ඉහළ දකුණේ ඇති <code>Received files</code> click කර save වූ file එක විවෘත කරන්න.</li>
          </ol>
        </div>
        """
        qs_b = """
        <div class="callout-card">
          <h3>ඉක්මන් ආරම්භය B: Windows → Android (QR හෝ Paired device මඟින් යැවීම)</h3>
          <ol>
            <li><strong>Android හි <code>Receive files</code> විවෘත කරන්න:</strong> Phone එකේ Glide open කර <code>Receive files</code> tap කරන්න.</li>
            <li><strong>Windows හි files එකතු කරන්න:</strong> Windows Glide හි <code>Send</code> පිටුවේ <code>Add files</code> හෝ <code>Add folder</code> මඟින් files තෝරන්න.</li>
            <li><strong>QR හෝ Paired device තෝරන්න:</strong> පළමු වරට යවන්නේ නම් <code>Quick share with QR</code> ඔබන්න (පෙර pair කළ phone එකක් නම් <code>Paired device</code> තෝරන්න).</li>
            <li><strong>Phone එකෙන් scan කරන්න (QR ක්‍රමයේදී):</strong> Android තිරයේ <code>Scan PC QR code</code> tap කර Windows හි QR එක scan කරන්න.</li>
            <li><strong>වැදගත් පියවර — Windows හි <code>Send files</code> ඔබන්න:</strong> Phone එක scan කළ පසු Windows හි <code>Confirm receiving device</code> පණිවිඩය එයි; එහි <code>Send files</code> අනිවාර්යයෙන්ම click කරන්න.</li>
            <li><strong>Phone එකේ <code>Accept file</code> tap කරන්න:</strong> Android හි <code>Accept file</code> ඔබා අවසන් වූ පසු <code>Verified</code> සලකුණ සමඟ <code>Download/Glide</code> හි save වේ.</li>
          </ol>
        </div>
        """
        perm_title = "<h3>Android අවසර (Permissions) — Glide අසන්නේ මොනවාද, ඇයි?</h3>"
        perm_headers = ["අවසරය (Permission)", "අසන අවස්ථාව", "අවශ්‍ය වන්නේ ඇයි", "Deny කළහොත් සිදුවන්නේ කුමක්ද (සහ විකල්ප)"]
        perm_rows = [
            ["<code>Nearby devices</code> (Bluetooth / Wi-Fi)", "<code>Send files</code> හෝ <code>Receive files</code> තේරූ විට", "අසල ඇති Android phones සෙවීමට සහ Wi-Fi Direct සම්බන්ධතා ඇති කිරීමට.", "Phone-to-phone සෙවීම අසාර්ථක වේ; නමුත් local Wi-Fi හරහා PC QR scan / link paste තවමත් භාවිතා කළ හැක."],
            ["<code>Camera</code>", "<code>Scan QR code</code> හෝ <code>Pair device</code> විවෘත කළ පළමු අවස්ථාවේදී", "Desktop තිරයේ පෙන්වන QR code පමණක් කියවීමට (කිසිදු ඡායාරූපයක් save නොවේ).", "QR scan කිරීමට නොහැක; ඒ වෙනුවට PC එකෙන් <code>Copy receive link</code> / <code>Copy link</code> කර Android හි <code>Paste invitation</code> භාවිතා කළ හැක."],
            ["<code>Notifications</code> (Android 13+)", "පසුබිමේ files ලැබීමට (<code>Keep receiving in background</code>) සක්‍රිය කළ විට", "තිරය වසා ඇති විට පවා transfer ප්‍රගතිය සහ <code>Stop</code> බොත්තම පෙන්වීමට.", "Transfer අඛණ්ඩව සිදුවුවත් notification තීරුවේ ප්‍රගතිය සහ ඉක්මන් cancel බොත්තම නොපෙනේ."],
            ["<code>Files / Photos</code> picker", "<code>Choose photos &amp; videos</code>, <code>Choose files &amp; documents</code> හෝ <code>Choose folder</code>", "ඔබ තෝරා දෙන ගොනු හෝ folder එක පමණක් කියවීමට Android system picker භාවිතා කරයි.", "සම්පූර්ණ දුරකථන ගබඩාවට පුළුල් අවසර අවශ්‍ය නොවේ; picker එකෙන් cancel කළහොත් කිසිදු file එකක් එකතු නොවේ."],
        ]
    else:
        lead = (
            "<p><strong>Glide 1.0.0</strong> transfers files directly between <strong>Windows, Ubuntu/Linux, and Android</strong> "
            "over your local Wi-Fi or Ethernet network—with no accounts, no cloud servers, and no internet connection required. "
            "Every button and tab name below matches the exact installed app interface.</p>"
        )
        qs_a = """
        <div class="callout-card">
          <h3>Quick start A: Android → Windows (your first transfer)</h3>
          <ol>
            <li><strong>Join the same network:</strong> Connect your phone and Windows PC to the same local Wi-Fi / LAN router.</li>
            <li><strong>Start receiving on Windows:</strong> Open Glide on Windows, open <code>Receive</code> in the sidebar, and click <code>Start receiving</code>.</li>
            <li><strong>Pick files on Android:</strong> Open Glide on Android and tap <code>Send files</code> → <code>Choose photos &amp; videos</code> or <code>Choose files &amp; documents</code> → <code>Proceed to send</code>.</li>
            <li><strong>Scan the Windows QR code:</strong> Tap <code>Scan QR code or paste invite</code> on Android, point at the Windows QR code, and tap <code>Connect</code>.</li>
            <li><strong>Approve on Windows:</strong> Check the sender and file count in the <code>Incoming transfer</code> dialog on Windows and click <code>Accept transfer</code>.</li>
            <li><strong>Open your files:</strong> Wait for completion, then click <code>Received files</code> in the top bar (or <code>Open folder</code> in Activity).</li>
          </ol>
        </div>
        """
        qs_b = """
        <div class="callout-card">
          <h3>Quick start B: Windows → Android</h3>
          <ol>
            <li><strong>Ready your phone:</strong> Open Glide on Android and tap <code>Receive files</code>.</li>
            <li><strong>Queue files on Windows:</strong> On the Windows <code>Send</code> tab, click <code>Add files</code> or <code>Add folder</code> to queue what you want to share.</li>
            <li><strong>Pick a send method:</strong> Click <code>Quick share with QR</code> for a one-time phone transfer (or pick a previously paired phone under <code>Paired device</code>).</li>
            <li><strong>Scan from Android (QR route):</strong> Tap <code>Scan PC QR code</code> on Android and scan the Windows Quick Share QR dialog.</li>
            <li><strong>Confirm recipient on Windows:</strong> After the phone scans, Windows displays <code>Confirm receiving device</code>—click <code>Send files</code> to authorize sending.</li>
            <li><strong>Accept on Android:</strong> Tap <code>Accept file</code> on the phone; verified files save automatically into <code>Download/Glide</code>.</li>
          </ol>
        </div>
        """
        perm_title = "<h3>Android permissions reference (asked only when needed)</h3>"
        perm_headers = ["Permission", "When it is asked", "Why Glide needs it", "What fails if denied (and workaround)"]
        perm_rows = [
            ["<code>Nearby devices</code> (Bluetooth / Wi-Fi / Local Network)", "When opening <code>Send files</code> or <code>Receive files</code>", "Discovers nearby Android phones via Google Nearby Connections and local LAN sockets.", "Phone-to-phone auto-discovery is blocked. LAN QR transfers to/from PC still work on API 26–36."],
            ["<code>Camera</code>", "First time opening the QR scanner from <code>Scan QR code</code> or <code>Pair device</code>", "Reads one-time QR codes locally without capturing or saving photos.", "Cannot scan QR codes; copy the link on PC instead and tap <code>Paste invitation</code> on Android."],
            ["<code>Notifications</code> (Android 13+)", "When turning on <code>Keep receiving in background</code> or background transfers", "Shows foreground progress notification and the background listener <code>Stop</code> action.", "Visibility only: transfers still work in foreground, but progress notification won't show."],
            ["<code>Files / Photos / Folder</code> system picker", "When tapping <code>Choose photos &amp; videos</code>, <code>Choose files &amp; documents</code>, or <code>Choose folder</code>", "Grants per-selection access only to items you explicitly pick.", "Cancelling leaves the send queue empty; Glide never requests broad all-files storage permission."],
        ]

    figs = (
        '<div class="fig-pair">' + fig("android", "home", si, used) + "</div>"
        + fig("windows", "send-empty", si, used)
    )
    return (
        lead
        + qs_a
        + qs_b
        + perm_title
        + _table_html(perm_headers, perm_rows, si)
        + figs
    )


def _section_2_screens(si: bool, used: set[tuple[str, str]]) -> str:
    if si:
        txt = """
        <p>Platforms තුනේම ප්‍රධාන කොටස් එකම ආකාරයට සකසා තිබුණද, බොත්තම් නම්වල කුඩා වෙනස්කම් ඇත:</p>
        <ul>
          <li><strong>Windows vs Linux Receive බොත්තම:</strong> Windows හි <code>Start receiving</code> / <code>Stop receiving</code> ලෙසත්, Ubuntu Linux හි <code>Turn on receiving</code> / <code>Turn off receiving</code> ලෙසත් පෙන්වයි.</li>
          <li><strong>Header හි <code>Received files</code> සහ <code>Sharing is local</code>:</strong> Windows සහ Linux දෙකෙහිම ඉහළ තීරුවේ ඇති <code>Received files</code> බොත්තමෙන් ලැබුණු ගොනු folder එක කෙලින්ම විවෘත වේ. Sidebar පහළ ඇති <code>Sharing is local</code> කාඩ්පත සෑම transfer එකක්ම අන්තර්ජාලයෙන් තොරව local ජාලයේ සිදුවන බව තහවුරු කරයි.</li>
          <li><strong>Android Home <code>Paired devices</code> ලැයිස්තුව:</strong> ඔබ පෙර pair කළ devices මෙහි <code>Paired</code> ලෙස හෝ අවසන් වරට සම්බන්ධ වූ වේලාව (<code>Last seen ...</code>) සමඟ දැක්වේ.</li>
          <li><strong>Light සහ Dark තේමා:</strong> Glide ඔබේ පද්ධති වර්ණ (Light හෝ Dark) අනුව ස්වයංක්‍රීයව හැඩගැසේ (උදා: පහත Android Light theme තිර).</li>
        </ul>
        """
    else:
        txt = """
        <p>Windows, Linux, and Android share the same core workflow while preserving platform-native control names:</p>
        <ul>
          <li><strong>Windows vs Linux Receive toggle:</strong> Windows uses <code>Start receiving</code> / <code>Stop receiving</code>, whereas Ubuntu Linux uses <code>Turn on receiving</code> / <code>Turn off receiving</code>. Android uses <code>Receive files</code> → <code>Ready to receive</code> / <code>Stop receiving</code>.</li>
          <li><strong>Header <code>Received files</code> &amp; <code>Sharing is local</code>:</strong> Desktop top bars provide a <code>Received files</code> shortcut straight to your download folder, while the <code>Sharing is local</code> card reminds you transfers stay inside your local LAN with no cloud relay.</li>
          <li><strong>Android Home <code>Paired devices</code>:</strong> Saved desktop peers appear right on Home showing <code>Paired</code> or a <code>Last seen ...</code> timestamp so you can verify saved connections at a glance.</li>
          <li><strong>Adaptive Light &amp; Dark themes:</strong> All three platforms support system Light and Dark themes (shown below on Linux Dark and Android Light captures).</li>
        </ul>
        """
    figs = (
        fig("linux", "send-empty", si, used)
        + fig("windows", "receive-ready", si, used)
        + fig("linux", "receive-ready", si, used)
        + '<div class="fig-pair">'
        + fig("android", "nearby-verify-light", si, used)
        + "</div>"
    )
    return txt + figs


def _section_3_pairing(si: bool, used: set[tuple[str, str]]) -> str:
    if si:
        txt = """
        <div class="callout-card warn">
          <h3>වැදගත් වෙනස: Pair-only QR vs Transfer QR</h3>
          <p><strong>Pair devices</strong> පිටුවේ ඇති QR code එක (<strong>Pair-only QR</strong>) භාවිතා කරන්නේ අනාගතයේදී ඉක්මනින් files යැවීම සඳහා devices දෙක අතර විශ්වාසය (trust) save කිරීමට පමණි — එයින් files යැවෙන්නේ නැත. එවෙලේම files යැවීමට නම් <code>Send</code> → <code>Quick share with QR</code> හෝ <code>Receive</code> පිටුවේ ඇති <strong>Transfer QR</strong> භාවිතා කරන්න.</p>
        </div>
        <h3>A. Desktop පරිගණක දෙකක් (Windows ↔ Linux / PC ↔ PC) pair කිරීම</h3>
        <ol>
          <li><strong>පළමු පරිගණකයේ:</strong> වම් මෙනුවේ <code>Pair devices</code> විවෘත කර <code>Show my QR and code</code> tab එක තෝරන්න. එහි පෙන්වන <strong>ඉලක්කම් 6 කේතය (6-digit code)</strong> තබාගන්න (කල් ඉකුත් වූවොත් <code>New code</code> ඔබන්න).</li>
          <li><strong>දෙවන පරිගණකයේ:</strong> <code>Pair devices</code> → <code>Find devices</code> වෙත ගොස් ලැයිස්තුවේ පෙනෙන අනෙක් PC එක click කරන්න.</li>
          <li><strong>කේතය ඇතුළු කරන්න:</strong> පළමු පරිගණකයේ පෙන්වන ඉලක්කම් 6 type කර <code>Pair</code> click කරන්න.</li>
          <li><strong>දෙපසම Approve කරන්න:</strong> ලැබෙන පණිවිඩයේ device නම නිවැරදි නම් <code>Approve</code> click කරන්න. එවිට <code>Done</code> සමඟ <code>Paired devices</code> ලැයිස්තුවට එක් වේ.</li>
          <li><strong>ස්වයංක්‍රීය සෙවීම අසාර්ථක නම්:</strong> <code>Enter pairing link</code> මඟින් copy කළ <code>glide://pair</code> link එක paste කරන්න, නැතිනම් <code>Pair by address</code> මඟින් අනෙක් PC එකේ local IP:port සහ ඉලක්කම් 6 කේතය ඇතුළු කරන්න.</li>
        </ol>
        <h3>B. Phone ↔ Desktop සහ Phone ↔ Phone තහවුරු කිරීම</h3>
        <ol>
          <li><strong>Android ↔ Desktop pair කිරීම:</strong> Desktop හි <code>Pair devices</code> → <code>Show my QR and code</code> විවෘත කර, Android Home තිරයේ <code>Pair device</code> tap කර එම pair-only QR එක scan කරන්න.</li>
          <li><strong>Android ↔ Android Nearby verification:</strong> Phone දෙකක් අතර යවන විට, තිර දෙකේම <strong>Verify connection</strong> යටතේ <strong>ඉලක්කම් 4 කේතයක් (4-digit code)</strong> පෙනේ. දෙපසම අංක සමාන නම් පමණක් <code>Numbers match</code> tap කරන්න; වෙනස් නම් <code>Don't match</code> ඔබා නවත්වන්න.</li>
        </ol>
        """
    else:
        txt = """
        <div class="callout-card warn">
          <h3>Crucial distinction: Pair-only QR vs Transfer QR</h3>
          <p>The QR under <strong>Pair devices</strong> (<strong>Pair-only QR</strong>) only enrolls a trusted cryptographic identity for future direct sends—it does <em>not</em> start a file transfer. To send or receive files immediately without saving permanent trust, use <code>Send</code> → <code>Quick share with QR</code> or the <code>Receive</code> tab's <strong>Transfer QR</strong>.</p>
        </div>
        <h3>A. Desktop ↔ Desktop pairing (Windows / Linux)</h3>
        <ol>
          <li><strong>On Computer 1:</strong> Open <code>Pair devices</code> → <code>Show my QR and code</code>. Keep the displayed <strong>6-digit code</strong> visible (use <code>New code</code> if expired, or <code>Copy link</code> to copy its URI).</li>
          <li><strong>On Computer 2:</strong> Open <code>Pair devices</code> → <code>Find devices</code> and select Computer 1 from the discovered list.</li>
          <li><strong>Enter the 6-digit PIN:</strong> Type the 6 digits shown on Computer 1 and click <code>Pair</code>.</li>
          <li><strong>Approve on both sides:</strong> Verify the device name on the incoming approval prompt, click <code>Approve</code>, then click <code>Done</code> once <code>Paired</code> appears.</li>
          <li><strong>Fallbacks when discovery misses:</strong> Switch to <code>Enter pairing link</code> and paste the copied link, or use <code>Pair by address</code> with the peer's LAN <code>IP:port</code> (from Settings / Connection details) and 6-digit code.</li>
        </ol>
        <h3>B. Phone ↔ Desktop pairing &amp; Phone ↔ Phone Nearby verification</h3>
        <ol>
          <li><strong>Phone ↔ Desktop:</strong> Display <code>Pair devices</code> → <code>Show my QR and code</code> on Windows or Linux, then tap <code>Pair device</code> on Android Home (or <code>Pair with another device</code> in Android Settings) and scan the pair-only QR.</li>
          <li><strong>Android ↔ Android 4-digit Nearby check:</strong> Nearby phone transfers show a 4-digit code under <code>Verify connection</code> on both phones. Compare aloud and tap <code>Numbers match</code> only if identical (otherwise tap <code>Don't match</code>).</li>
        </ol>
        """

    figs = (
        fig("windows", "pair-find", si, used)
        + fig("linux", "pair-find", si, used)
        + fig("linux", "pair-show-qr", si, used)
        + fig("windows", "pair-show-qr", si, used)
        + fig("windows", "pair-enter-code", si, used)
        + fig("linux", "pair-enter-code", si, used)
        + fig("windows", "pair-approval", si, used)
        + fig("windows", "pair-complete", si, used)
        + fig("linux", "pair-complete", si, used)
        + fig("windows", "pair-enter-link", si, used)
        + fig("linux", "pair-enter-link", si, used)
        + fig("windows", "pair-by-address", si, used)
        + fig("linux", "pair-by-address", si, used)
        + '<div class="fig-pair">'
        + fig("android", "nearby-verify", si, used)
        + "</div>"
    )
    return txt + figs


def _section_4_send_receive(si: bool, used: set[tuple[str, str]]) -> str:
    if si:
        txt = """
        <h3>A. Files තෝරාගැනීම සහ කළමනාකරණය (Android &amp; Desktop)</h3>
        <ol>
          <li><strong>Desktop (Windows/Linux):</strong> <code>Send</code> පිටුවේ <code>Add files</code> / <code>Add folder</code> ඔබන්න. තනි file එකක් අස් කිරීමට Windows හි <code>Remove</code> හෝ Linux හි <code>✕</code> ඔබන්න; සියල්ල ඉවත් කිරීමට Windows හි <code>Clear list</code> හෝ Linux හි <code>Clear all</code> click කරන්න.</li>
          <li><strong>Android:</strong> <code>Send files</code> → <code>Choose photos &amp; videos</code>, <code>Choose files &amp; documents</code> හෝ <code>Choose folder</code> මඟින් තෝරා, <code>Remove</code> / <code>Clear all</code> මඟින් සකසා, <code>Proceed to send</code> tap කරන්න.</li>
        </ol>
        <h3>B. ලබන්නා තේරීම සහ QR සැසිය තහවුරු කිරීම</h3>
        <ol>
          <li><strong>Paired device කෙනෙකුට යැවීම:</strong> Desktop හි <code>Paired device</code> මඟින් save කළ device එක තෝරන්න (නැවත QR scan අවශ්‍ය නැත). Android හිදී <code>Select recipient</code> තුළ ළඟ ඇති phone එක tap කරන්න.</li>
          <li><strong>QR මඟින් එක් වරක් යැවීම (Desktop → Phone):</strong> Desktop හි <code>Quick share with QR</code> click කරන්න (අවශ්‍ය නම් <code>Refresh code</code> හෝ <code>Cancel QR share</code> / <code>Cancel quick share</code> භාවිතා කරන්න).</li>
          <li><strong>Phone එකෙන් scan කිරීම:</strong> Android හි <code>Receive files</code> → <code>Scan PC QR code</code> මඟින් එම QR එක scan කරන්න.</li>
          <li><strong>වැදගත්ම පියවර (Sender confirmation):</strong> Phone එක scan කළ විගස, <strong>Windows හි <code>Confirm receiving device</code> → <code>Send files</code></strong> (හෝ <code>Reject</code>) ලෙසත්, <strong>Linux හි <code>Confirm Recipient</code></strong> ලෙසත් පෙන්වයි. <strong>යවන්නා මෙම <code>Send files</code> / <code>Confirm Recipient</code> බොත්තම ඔබන තුරු files යැවීම ආරම්භ නොවේ.</strong></li>
        </ol>
        <h3>C. ලැබෙන Transfer එක අනුමත කිරීම</h3>
        <ol>
          <li><strong>ලැබීම සූදානම් කිරීම:</strong> Windows හි <code>Start receiving</code>, Linux හි <code>Turn on receiving</code>, හෝ Android හි <code>Receive files</code> විවෘත කර තබන්න (විශාල QR සඳහා desktop හි <code>Show larger code</code> භාවිතා කරන්න).</li>
          <li><strong>අනුමැතිය දීම:</strong> සෑම transfer එකකදීම ලබන්නා විසින් Windows හි <code>Accept transfer</code>, Linux හි <code>Accept Transfer</code>, හෝ Android හි <code>Accept file</code> tap කළ යුතුය. Glide කිසි විටෙක ඔබේ අනුමැතියෙන් තොරව files භාර නොගනී.</li>
          <li><strong>සත්‍යාපනය අවසන් වීම:</strong> Progress 100% වූ පසු Glide සෑම file එකකම SHA-256 පරීක්ෂා කර <code>Verified</code> සමඟ save කරයි; Android හි <code>Open file</code>, <code>Share file</code> සහ <code>Done</code> ලැබේ.</li>
        </ol>
        """
        route_hdr = "<h3>D. සියලුම Device මාර්ග 9 (Platform 3 × 3 මාර්ගෝපදේශය)</h3>"
    else:
        txt = """
        <h3>A. Selecting and editing files</h3>
        <ol>
          <li><strong>On desktop (Windows / Linux):</strong> Use <code>Add files</code> / <code>Add folder</code> on <code>Send</code>. Remove one entry with Windows <code>Remove</code> or Linux <code>✕</code>; empty the entire batch with Windows <code>Clear list</code> or Linux <code>Clear all</code>.</li>
          <li><strong>On Android:</strong> Tap <code>Send files</code>, add items via <code>Choose photos &amp; videos</code>, <code>Choose files &amp; documents</code>, or <code>Choose folder</code>, adjust with <code>Remove</code> / <code>Clear all</code>, and tap <code>Proceed to send</code>.</li>
        </ol>
        <h3>B. Choosing a target &amp; confirming QR recipients</h3>
        <ol>
          <li><strong>Sending to a saved or nearby device:</strong> On desktop, choose a saved peer under <code>Paired device</code> (no QR needed). On Android, tap the receiving phone in <code>Select recipient</code>.</li>
          <li><strong>One-time send via QR (Desktop → Phone):</strong> Click <code>Quick share with QR</code> on desktop (use <code>Refresh code</code> if expired, or <code>Cancel QR share</code> on Windows / <code>Cancel quick share</code> on Linux to back out).</li>
          <li><strong>Phone scans the sender QR:</strong> On Android, tap <code>Receive files</code> → <code>Scan PC QR code</code> (or <code>Paste invitation</code> in the link dialog).</li>
          <li><strong>Mandatory sender confirmation after scan:</strong> Right after the phone scans, <strong>Windows opens <code>Confirm receiving device</code> with <code>Reject</code> / <code>Send files</code></strong>, and <strong>Linux opens <code>Confirm Recipient</code> with <code>Cancel</code> / <code>Confirm Recipient</code></strong>. The sender <strong>must press <code>Send files</code> (Windows) or <code>Confirm Recipient</code> (Linux)</strong> before Glide reveals or transmits any files.</li>
        </ol>
        <h3>C. Receiving &amp; accepting every offer</h3>
        <ol>
          <li><strong>Listen for transfers:</strong> Keep <code>Receive</code> active via Windows <code>Start receiving</code>, Linux <code>Turn on receiving</code>, or Android <code>Receive files</code>. Use <code>Show larger code</code> or <code>Copy receive link</code> on desktop when sending from Android.</li>
          <li><strong>Explicit receiver consent:</strong> Even between paired devices, Glide never auto-accepts. The receiver must click Windows <code>Accept transfer</code>, Linux <code>Accept Transfer</code>, or Android <code>Accept file</code>.</li>
          <li><strong>Verification commit:</strong> Wait for streaming SHA-256 verification to finish and show <code>Verified</code> / <code>Completed</code>. On Android, use <code>Open file</code>, <code>Share file</code>, or <code>Done</code>.</li>
        </ol>
        """
        route_hdr = "<h3>D. All 9 platform transfer routes</h3>"

    figs = (
        '<div class="fig-pair">'
        + fig("android", "send-select-empty", si, used)
        + fig("android", "send-select-staged", si, used)
        + "</div>"
        + fig("linux", "send-staged", si, used)
        + '<div class="fig-pair">'
        + fig("android", "recipient-list", si, used)
        + fig("android", "send-qr-confirmation", si, used)
        + "</div>"
        + fig("windows", "send-paired-picker", si, used)
        + fig("windows", "send-qr-dialog", si, used)
        + fig("linux", "send-qr-dialog", si, used)
        + '<div class="fig-pair">'
        + fig("android", "send-progress", si, used)
        + "</div>"
        + fig("windows", "receive-large-qr", si, used)
        + fig("windows", "receive-offer", si, used)
        + fig("linux", "receive-offer", si, used)
        + '<div class="fig-pair">'
        + fig("android", "receive-offer", si, used)
        + fig("android", "receive-complete", si, used)
        + "</div>"
    )
    routes_html = '<div class="route-grid">' + "".join(route_card(a, c, si) for a, c in ROUTES) + "</div>"
    return txt + figs + route_hdr + routes_html


def _section_5_received_files(si: bool, used: set[tuple[str, str]]) -> str:
    if si:
        txt = """
        <ol>
          <li><strong>Android හි ස්ථානය:</strong> සත්‍යාපනය වූ සෑම file එකක්ම පෙරනිමියෙන් phone එකේ <code>Download/Glide</code> folder එකට save වේ. එය වෙනස් කිරීමට <code>Settings</code> → <code>Received file save location</code> → <code>Choose folder</code> tap කරන්න (පසුව <code>Use Download/Glide</code> මඟින් ආපසු මුල් folder එකට මාරු විය හැක).</li>
          <li><strong>Windows හි ස්ථානය:</strong> ඉහළ තීරුවේ ඇති <code>Received files</code> බොත්තම click කිරීමෙන් කෙලින්ම folder එක විවෘත වේ; නැතිනම් <code>Settings</code> → <code>Save received files to</code> → <code>Change folder</code> හෝ <code>Activity</code> → <code>Open folder</code> භාවිතා කරන්න.</li>
          <li><strong>Linux හි ස්ථානය:</strong> ඉහළ ඇති <code>Received files</code> බොත්තම හෝ <code>Settings</code> → <code>Download Directory</code> → <code>Browse...</code> මඟින් save ස්ථානය බලා වෙනස් කරන්න.</li>
          <li><strong>ආරක්ෂිතව save වන ආකාරය (Quarantine staging):</strong> File එක 100% ලැබී SHA-256 නිවැරදි බව තහවුරු වන තුරු තාවකාලික <code>.part</code> ගොනුවක තබා අවසානයේ පමණක් ඔබේ destination folder එකට යොමු කරයි.</li>
        </ol>
        """
    else:
        txt = """
        <ol>
          <li><strong>On Android:</strong> Verified files automatically save to <code>Download/Glide</code>. To change this, open <code>Settings</code> → <code>Received file save location</code> → <code>Choose folder</code> (tap <code>Use Download/Glide</code> anytime to reset).</li>
          <li><strong>On Windows:</strong> Click <code>Received files</code> in the top header bar, click <code>Open folder</code> on any item in <code>Activity</code>, or change the folder under <code>Settings</code> → <code>Save received files to</code> → <code>Change folder</code>.</li>
          <li><strong>On Linux:</strong> Click <code>Received files</code> in the <code>Activity</code> header bar or view/change <code>Download Directory</code> with <code>Browse...</code> in <code>Settings</code>.</li>
          <li><strong>Safe atomic commits:</strong> Glide writes incoming bytes to private quarantine staging (<code>.part</code>), verifies the whole-file SHA-256 digest, and moves the verified file into your destination folder only after confirmation.</li>
        </ol>
        """
    figs = (
        '<div class="fig-pair">'
        + fig("android", "settings-storage", si, used)
        + "</div>"
        + fig("linux", "settings-upper", si, used)
    )
    return txt + figs


def _section_6_activity(si: bool, used: set[tuple[str, str]]) -> str:
    if si:
        txt = """
        <ol>
          <li><strong>සජීවී transfers නිරීක්ෂණය:</strong> යැවෙන හෝ ලැබෙන අතරතුර desktop <code>Activity</code> පිටුවේ ප්‍රතිශතය සහ වේගය පෙනේ. අවශ්‍ය නම් Windows හි <code>Cancel</code> හෝ Linux හි <code>Cancel transfer</code> ඔබා ආරක්ෂිතව නැවැත්විය හැක.</li>
          <li><strong>පෙරහන් සහ සෙවීම:</strong> Desktop <code>Activity</code> හි <code>Search history</code> කොටුව හෝ <code>All</code> / <code>Sent</code> / <code>Received</code> tabs භාවිතා කරන්න. Android <code>History</code> තුළද එම <code>All</code> / <code>Sent</code> / <code>Received</code> filter chips ඇත.</li>
          <li><strong>Save වූ files විවෘත කිරීම:</strong> Desktop හි <code>Completed</code> සටහන ඉදිරියේ ඇති <code>Open folder</code> click කරන්න. Android <code>History</code> හි ඕනෑම entry එකක් tap කර file එක open හෝ share කරන්න.</li>
          <li><strong>ඉතිහාසය පිරිසිදු කිරීම:</strong> Desktop හි <code>Clear history</code> (හෝ Android <code>History</code> හි trash icon එක) මඟින් ලැයිස්තුව මැකුවද ඔබේ save වූ files මැකෙන්නේ නැත.</li>
        </ol>
        """
    else:
        txt = """
        <ol>
          <li><strong>Monitor active transfers:</strong> Desktop <code>Activity</code> displays the live transfer card with byte progress, speed, and a Windows <code>Cancel</code> or Linux <code>Cancel transfer</code> button.</li>
          <li><strong>Search and filter history:</strong> Desktop <code>Activity</code> provides <code>Search history</code> plus <code>All</code> / <code>Sent</code> / <code>Received</code> filters; Android <code>History</code> provides matching <code>All</code> / <code>Sent</code> / <code>Received</code> filter chips.</li>
          <li><strong>Open verified items:</strong> On desktop, click <code>Open folder</code> beside any <code>Completed</code> entry. On Android, tap any session row in <code>History</code> to open, share, or export its files.</li>
          <li><strong>Clear history without deleting files:</strong> Clicking desktop <code>Clear history</code> or Android's History trash icon clears log entries only; your received files remain safe in storage.</li>
        </ol>
        """
    figs = (
        fig("windows", "activity-receiving", si, used)
        + fig("linux", "activity-sending", si, used)
        + fig("windows", "activity-history", si, used)
        + fig("linux", "activity-history", si, used)
        + '<div class="fig-pair">'
        + fig("android", "history", si, used)
        + "</div>"
    )
    return txt + figs


def _section_7_settings(si: bool, used: set[tuple[str, str]]) -> str:
    if si:
        txt = """
        <ol>
          <li><strong>Windows Settings:</strong> <code>Appearance</code> (System/Light/Dark), <code>Save received files to</code> + <code>Change folder</code>, <code>Advanced connection details</code> (Local IP, port සහ fingerprint බැලීමට), සහ <code>Trusted devices</code> යටතේ <code>Remove</code> බොත්තම.</li>
          <li><strong>Linux Settings:</strong> <code>Appearance</code>, <code>Device Name</code>, <code>Download Directory</code> + <code>Browse...</code>, <code>Trusted Devices Details</code> + <code>Revoke selected trusted device</code>, <code>Listening Port</code>, <code>Export diagnostic logs</code>, සහ <code>Glide for Linux</code> තොරතුරු.</li>
          <li><strong>Android Settings:</strong> <code>Edit</code> (device name), <code>Theme mode</code>, <code>Dynamic color</code>, <code>Reduced motion</code>, <code>Notification details</code>, <code>Keep screen awake</code>, <code>Manage trusted devices</code>, <code>Pair with another device</code>, <code>Delete all</code>, <code>Received file save location</code>, <code>Nearby diagnostics</code>, <code>Privacy policy</code>, සහ <code>Open source licenses</code>.</li>
          <li><strong>Android පසුබිමේ ලැබීම (Background receiving):</strong> <code>Receive files</code> තිරයේ ඇති <code>Keep receiving in background</code> on කළ විට app එකෙන් ඉවත් වුවත් notification එකක් සමඟ files භාරගැනීමට සූදානම්ව සිටී; නැවැත්වීමට notification හෝ තිරයේ <code>Stop</code> / <code>Stop receiving</code> ඔබන්න.</li>
        </ol>
        """
    else:
        txt = """
        <ol>
          <li><strong>Windows Settings:</strong> Configure <code>Appearance</code>, change <code>Save received files to</code> via <code>Change folder</code>, inspect local IP/port/fingerprint under <code>Advanced connection details</code>, and revoke saved peers under <code>Trusted devices</code> → <code>Remove</code>.</li>
          <li><strong>Linux Settings:</strong> Adjust <code>Appearance</code> (Follow System / Light / Dark), edit <code>Device Name</code>, pick <code>Download Directory</code> via <code>Browse...</code>, inspect/revoke peers via <code>Trusted Devices Details</code> + <code>Revoke selected trusted device</code>, check <code>Listening Port</code>, or click <code>Export diagnostic logs</code>.</li>
          <li><strong>Android Settings:</strong> Rename your phone via <code>Edit</code>, adjust <code>Theme mode</code>, <code>Dynamic color</code>, <code>Reduced motion</code>, <code>Notification details</code>, and <code>Keep screen awake</code>, manage pairs in <code>Manage trusted devices</code> / <code>Pair with another device</code>, clear app data with <code>Delete all</code>, or run <code>Nearby diagnostics</code>.</li>
          <li><strong>Background receiving on Android:</strong> Turning on <code>Keep receiving in background</code> inside <code>Receive files</code> keeps listening with a persistent notification and a <code>Stop</code> action. By default, idle listening stops when Glide leaves the foreground, while active transfers run to completion.</li>
        </ol>
        """
    figs = (
        fig("windows", "settings", si, used)
        + fig("linux", "settings-lower", si, used)
        + '<div class="fig-pair">'
        + fig("android", "settings-appearance", si, used)
        + fig("android", "settings-trust", si, used)
        + "</div>"
    )
    return txt + figs


def _section_8_troubleshooting(si: bool, used: set[tuple[str, str]]) -> str:
    if si:
        entries = [
            (
                "Bluetooth අක්‍රියයි (Bluetooth off — Android)",
                "Android හි Nearby phone සෙවීමට Bluetooth අවශ්‍ය වේ. <code>Open Bluetooth settings</code> tap කර Bluetooth on කර, Glide වෙත ආපසු පැමිණ <code>Check again</code> tap කරන්න. PC එකකට යවන්නේ නම් Bluetooth නොමැතිව <code>Scan QR code or paste invite</code> හරහා local Wi-Fi මඟින් යැවිය හැක.",
            ),
            (
                "Wi-Fi අක්‍රියයි හෝ වෙනත් ජාලයක (Wi-Fi off / wrong network)",
                "<code>Open Wi-Fi settings</code> tap කර අනෙක් device එක සම්බන්ධ වී ඇති local Wi-Fi ජාලයටම සම්බන්ධ වී <code>Check again</code> ඔබන්න. Mobile data හරහා Glide වැඩ නොකරයි.",
            ),
            (
                "අසල devices කිසිවක් නොපෙනේ (No devices found)",
                "ලබන phone එකේ Glide open කර <code>Receive files</code> තුළ ඇති බව තහවුරු කර Android හි <code>Retry</code> tap කරන්න. Desktop pair කිරීමේදී හමු නොවුණහොත් Linux/Windows හි <code>Try again</code>, <code>Pair by address</code> (IP:port + කේතය), හෝ <code>Enter pairing link</code> භාවිතා කරන්න.",
            ),
            (
                "QR code හෝ ඉලක්කම් 6 කේතය කල් ඉකුත් වී ඇත (Expired code or link)",
                "ආරක්ෂාව සඳහා invitations තාවකාලික වේ. පෙන්වන device එකේ <code>Refresh code</code> (Receive / Quick Share) හෝ <code>New code</code> (Pair devices) ඔබා අලුත් code එක නැවත scan හෝ type කරන්න.",
            ),
            (
                "Transfer එක අවලංගු හෝ අසාර්ථක විය (Cancelled or failed)",
                "<code>Activity</code> (Desktop) හෝ <code>History</code> (Android) විවෘත කර <code>Technical details</code> පරීක්ෂා කරන්න. ජාලය, storage ඉඩ සහ firewall (Listening Port) පරීක්ෂා කර <code>Try again</code> ඇති විට නැවත උත්සාහ කරන්න. අවලංගු කළ transfer කිසි විටෙක අර්ධ ලෙස save නොවේ.",
            ),
            (
                "ලැබුණු file එක සොයාගත නොහැක (Saved file not found)",
                "Android හි <code>Download/Glide</code> folder එක හෝ <code>Settings</code> → <code>Received file save location</code> පරීක්ෂා කරන්න. Windows හෝ Linux හි ඉහළ තීරුවේ <code>Received files</code> හෝ Activity හි <code>Open folder</code> click කරන්න.",
            ),
        ]
    else:
        entries = [
            (
                "Bluetooth is turned off (Android)",
                "Tap <code>Open Bluetooth settings</code>, turn Bluetooth on, return to Glide, and tap <code>Check again</code>. If transferring to Windows or Linux over local Wi-Fi, you can still use <code>Scan QR code or paste invite</code>.",
            ),
            (
                "Wi-Fi is turned off or on the wrong network",
                "Tap <code>Open Wi-Fi settings</code> and join the exact same local Wi-Fi network as the peer (mobile cellular data does not carry local Glide transfers), then tap <code>Check again</code>.",
            ),
            (
                "No nearby devices found",
                "Confirm the receiving phone has Glide open on <code>Receive files</code> and tap <code>Retry</code> on Android. Between desktops, confirm the other computer is on <code>Show my QR and code</code> and try <code>Try again</code>, <code>Pair by address</code> (using Settings IP + <code>Listening Port</code>), or <code>Enter pairing link</code>.",
            ),
            (
                "QR code, 6-digit PIN, or link expired",
                "Invitations are single-use and time-limited for security. Click <code>Refresh code</code> (on Receive / Quick Share) or <code>New code</code> (under Pair devices) on the displaying device and scan or paste the fresh code.",
            ),
            (
                "Transfer cancelled or interrupted",
                "Open <code>Activity</code> (desktop) or <code>History</code> (Android) and expand <code>Technical details</code>. Verify both devices remain on the same Wi-Fi/LAN with enough disk space, and use <code>Try again</code> where shown. Cancelled items never leave partial files in your destination folder.",
            ),
            (
                "Saved file not found",
                "On Android, check <code>Download/Glide</code> or <code>Settings</code> → <code>Received file save location</code>. On Windows or Linux, click <code>Received files</code> in the header bar or <code>Open folder</code> beside the completed row in <code>Activity</code>.",
            ),
        ]

    cards = "".join(f"<dt>{html.escape(k)}</dt><dd>{v}</dd>" for k, v in entries)
    figs = (
        '<div class="fig-pair">'
        + fig("android", "recipient-bluetooth-off", si, used)
        + fig("android", "recipient-wifi-off", si, used)
        + fig("android", "recipient-no-devices", si, used)
        + "</div>"
        + fig("linux", "pair-not-found", si, used)
        + fig("windows", "activity-cancelled", si, used)
    )
    return f"<dl class=\"trouble-list\">{cards}</dl>" + figs


def _section_9_faq_limits_glossary(si: bool) -> str:
    if si:
        faqs = [
            ("1. මට අන්තර්ජාලය (Internet) හෝ ගිණුමක් (Account) අවශ්‍යද?", "නැත. Glide 100% අන්තර්ජාලයෙන් තොරව (offline) ක්‍රියා කරයි. එකම Wi-Fi / LAN ජාලය හෝ Nearby Connections පමණක් ප්‍රමාණවත්ය; login වීම හෝ cloud storage භාවිතයක් නැත."),
            ("2. Glide නොමිලේ ලබා ගත හැකිද?", "ඔව්. Glide 1.0.0 සම්පූර්ණයෙන්ම නොමිලේ ලබා දෙන විවෘත කේත (open-source) මෘදුකාංගයකි."),
            ("3. මෙය ආරක්ෂිත සහ පෞද්ගලිකද?", "ඔව්. LAN හරහා සෑම transfer එකක්ම TLS 1.3 සහ SPKI public-key pinning මඟින් encrypt වේ. ලැබෙන files ප්‍රථමයෙන් පෞද්ගලික staging ප්‍රදේශයක SHA-256 පරීක්ෂාවට ලක් කර නිවැරදි නම් පමණක් save කරයි."),
            ("4. සෑම transfer එකක්ම අනුමත කිරීමට සිදුවන්නේ ඇයි (QR scan කළ පසුවත්)?", "වැරදීමකින් හෝ ඔබේ කැමැත්තෙන් තොරව කිසිවෙකුට files එවීම වැළැක්වීමටය. එම නිසා ලබන්නා <code>Accept file</code> / <code>Accept transfer</code> එබිය යුතු අතර, Quick Share QR scan කළ පසු යවන්නාද Windows හි <code>Send files</code> (Linux හි <code>Confirm Recipient</code>) මඟින් තහවුරු කළ යුතුය."),
            ("5. <code>Verified</code> යන්නෙන් අදහස් කරන්නේ කුමක්ද?", "ඔබේ device එකට ලැබුණු file එකේ එක් byte එකක්වත් වෙනස් නොවී මුල් file එකට 100% සමාන බව SHA-256 checksum මඟින් තහවුරු කළ බවයි."),
            ("6. QR වර්ග දෙකක් (Pair-only QR සහ Transfer QR) තිබෙන්නේ ඇයි?", "<code>Pair devices</code> තුළ ඇති <strong>Pair-only QR</strong> එක දිගුකාලීනව devices හඳුනාගැනීම save කිරීමට පමණි. එවෙලේම files හුවමාරු කිරීමට <code>Quick share with QR</code> හෝ <code>Receive</code> හි ඇති <strong>Transfer QR</strong> භාවිතා කරයි."),
            ("7. ලැබුණු files save වන්නේ කොතැනටද?", "Android හි පෙරනිමියෙන් <code>Download/Glide</code> වෙත save වේ (Settings තුළ වෙනස් කළ හැක). Desktop හි Settings තුළ තෝරා ඇති folder එකට save වන අතර <code>Received files</code> බොත්තමෙන් කෙලින්ම විවෘත කළ හැක."),
            ("8. QR code එක හෝ ඉලක්කම් 6 කේතය කල් ඉකුත් වුවහොත් කුමක් කළ යුතුද?", "ආරක්ෂාව නිසා කේත මිනිත්තු කිහිපයකින් කල් ඉකුත් වේ. පෙන්වන device එකේ <code>Refresh code</code> හෝ <code>New code</code> click කර අලුත් එකක් ලබාගන්න."),
        ]
        lim_title = "<h3>තාක්ෂණික සීමාවන් (Glide 1.0.0 Limits)</h3>"
        lim_headers = ["සීමාව (Rule)", "උපරිම අගය", "පැහැදිලි කිරීම"]
        lim_rows = [
            ["එක් වරකට යැවිය හැකි items ගණන", "1,000 items", "එක් batch එකක උපරිම files 1,000 ක් පමණක් යැවිය හැක (folder තුළ උපරිම ගොනු 10,000 / ගැඹුර 16)."],
            ["තනි file එකක උපරිම ප්‍රමාණය", "64 GiB", "තනි file එකක් 64 GiB දක්වා සහය දක්වයි (disk ඉඩ ප්‍රමාණවත් විය යුතුය)."],
            ["මුළු batch එකක උපරිම ප්‍රමාණය", "256 GiB", "එක් සම්පූර්ණ transfer batch එකක මුළු ප්‍රමාණය 256 GiB නොඉක්මවිය යුතුය."],
            ["Protocol frames සහ security", "16 KiB / 64 KiB", "Control පණිවිඩ 16 KiB ද, data කොටස් 64 KiB ද වන අතර අනාරක්ෂිත Windows file නම් (CON, NUL, ../) ප්‍රතික්ෂේප වේ."],
            ["ජාල අවශ්‍යතාවය", "Local LAN / Wi-Fi", "Internet හරහා නොව, එකම local Wi-Fi / Ethernet ජාලය හෝ Android Nearby හරහා ක්‍රියා කරයි."],
        ]
        apple_note = (
            '<div class="callout-card"><h3>iOS / iPadOS සහ macOS තත්ත්වය</h3>'
            '<p>Glide 1.0.0 මේ වන විට <strong>Windows, Ubuntu/Linux සහ Android</strong> සඳහා සම්පූර්ණයෙන් ක්‍රියාත්මක වන අතර මෙම මාර්ගෝපදේශයේ ඒවායේ සැබෑ තිර ඡායාරූප ඇතුළත් වේ. Apple (iPhone, iPad, Mac) සඳහා Core පද්ධතිය සහ Simulators පරීක්ෂා කර ඇතත්, physical devices සඳහා බෙදාහැරීම තවමත් සූදානම් වෙමින් පවතින බැවින් iOS/macOS තිර ඡායාරූප මෙහි නොමැත.</p></div>'
        )
        gl_title = "<h3>වචන මාලාව (Glossary)</h3>"
        gl_items = [
            ("Trusted identity", "<code>Pair devices</code> හරහා දෙපසම අනුමත කර save කරගත් device හඳුනාගැනීම (අනාගතයේ QR scan නොකර යැවිය හැක)."),
            ("Fingerprint / SAS", "Device එකේ public key එක නියෝජනය කරන කෙටි අංකය; Android Nearby හි ඉලක්කම් 4 (<code>Numbers match</code>) ලෙසද, desktop හි ඉලක්කම් 6 pairing කේතය ලෙසද භාවිතා වේ."),
            ("Transfer QR vs Pair-only QR", "<strong>Transfer QR</strong> යනු එවෙලේ file යැවීමට/ගැනීමට සාදන එක්-වරක් භාවිතා වන QR එකකි; <strong>Pair-only QR</strong> යනු විශ්වාසය save කිරීමට පමණි."),
            ("Verified", "ලැබුණු file එකේ SHA-256 ගණනය මුල් file එකට සම්පූර්ණයෙන් සමාන බව තහවුරු වූ තත්ත්වය."),
            ("Quarantine staging (.part)", "Verification සම්පූර්ණ වන තුරු අසම්පූර්ණ files ඔබේ සැබෑ folder එකෙන් පිටත ආරක්ෂිතව තබාගන්නා ක්‍රමය."),
            ("Local address / Listening Port", "ස්වයංක්‍රීය සෙවීම (mDNS) අවහිර ජාලවලදී <code>Pair by address</code> මඟින් කෙලින්ම සම්බන්ධ වීමට යොදාගන්නා IP ලිපිනය සහ port අංකය."),
        ]
    else:
        faqs = [
            ("1. Do I need internet or an account?", "No. Glide runs 100% offline over your local Wi-Fi/Ethernet network (or Android Nearby radios). No sign-in, cloud relay, or internet access is used."),
            ("2. Is Glide free?", "Yes. Glide 1.0.0 is free, local-first software with no subscriptions, ads, or size paywalls."),
            ("3. Is it safe and private?", "Yes. LAN transfers use TLS 1.3 with SHA-256 SPKI certificate pinning. Incoming bytes stream into private .part staging, verify against SHA-256, and rename into your folder only when verified."),
            ("4. Why must I approve every transfer (and confirm after QR scan)?", "Manual approval prevents unwanted files from ever landing on your device. Receivers must click Accept, and after scanning a Quick Share QR the desktop sender must also confirm the receiving device."),
            ("5. What does 'Verified' mean?", "It confirms Glide computed a byte-for-byte SHA-256 digest of the received file and matched the sender's checksum before revealing it."),
            ("6. Why are there two kinds of QR codes?", "The Pair devices QR saves long-term trust between two devices without transferring files. The Receive / Quick share QR creates a single-use connection for an immediate file transfer."),
            ("7. Where do received files go?", "Android saves to Download/Glide by default (changeable under Settings). Windows and Linux save to the folder shown in Settings, reachable anytime via the Received files header button."),
            ("8. What if a QR code, link, or 6-digit PIN expired?", "Pairing and QR tokens are short-lived and single-use. Click Refresh code (Receive/Quick Share) or New code (Pair devices) on the displaying device for a fresh token."),
        ]
        lim_title = "<h3>Limits and protocol guardrails</h3>"
        lim_headers = ["Limit", "Maximum", "Details"]
        lim_rows = [
            ["Items per transfer batch", "1,000 items", "Maximum 1,000 items in a batch offer (folder selection caps at 10,000 nested files and depth 16)."],
            ["Single file size", "64 GiB", "Each item may be up to 64 GiB (68,719,476,736 bytes), subject to receiver disk space."],
            ["Total batch size", "256 GiB", "Total bytes across all files in one session cannot exceed 256 GiB."],
            ["Protocol frames & filename safety", "16 KiB / 64 KiB", "Control payloads are capped at 16 KiB, stream data chunks at 64 KiB; path traversal and reserved Windows DOS names are rejected."],
            ["Network scope", "Same local LAN / Nearby", "No remote internet relaying; devices must share a local subnet or Android Nearby proximity."],
        ]
        apple_note = (
            '<div class="callout-card"><h3>Apple (iOS / iPadOS / macOS) availability note</h3>'
            '<p>Glide 1.0.0 establishes Windows, Ubuntu/Linux, and Android clients (captured in this guide). While the Apple Swift core (`GlideCoreKit`) and simulator backends have been verified in CI, signed physical-device iOS/macOS builds remain an open release gate (see <code>doc/planning/native-platforms/RECOVERY_STATUS.md</code>), so Apple screenshots are omitted here.</p></div>'
        )
        gl_title = "<h3>Glossary</h3>"
        gl_items = [
            ("Trusted identity", "A saved peer public-key fingerprint enrolled via Pair devices so future sends can connect directly without scanning QR codes."),
            ("Fingerprint / SAS", "Cryptographic verification digits: 4 digits compared aloud on Android Nearby (Numbers match), or a 6-digit one-time pairing code on desktops."),
            ("Transfer QR vs Pair-only QR", "Transfer QR authorizes one immediate file batch (Quick share with QR / Receive); Pair-only QR enrolls long-term trust under Pair devices without sending files."),
            ("Verified", "Indicates whole-file SHA-256 digest verification matched before the file was committed to your destination folder."),
            ("Quarantine staging (.part)", "Private staging directory where incoming files reside until SHA-256 passes and path safety checks succeed."),
            ("Local address / Listening Port", "Your device's LAN IPv4 address and TCP port used as a manual fallback under Pair by address when mDNS discovery is blocked."),
        ]

    faq_html = "".join(
        f'<details class="faq-item"><summary>{html.escape(q)}</summary><p>{a}</p></details>'
        for q, a in faqs
    )
    if si:
        for q, a in faqs:
            if not has_sinhala(q) or not has_sinhala(a):
                raise ValueError(f"FAQ missing Sinhala: {q!r}")
        for k, v in gl_items:
            if not has_sinhala(v):
                raise ValueError(f"Glossary missing Sinhala: {k!r}")

    gl_html = '<dl class="glossary-list">' + "".join(
        f"<dt>{html.escape(k)}</dt><dd>{v}</dd>" for k, v in gl_items
    ) + "</dl>"

    return (
        faq_html
        + lim_title
        + _table_html(lim_headers, lim_rows, si)
        + apple_note
        + gl_title
        + gl_html
    )


def _section_10_appendix(si: bool) -> str:
    if si:
        win_hdr = ["තිරය (Screen)", "Control නම", "කාර්යය"]
        win_rows = [
            ["Top bar (සෑම තිරයකම)", "<code>Received files</code>", "ලැබුණු files save වන folder එක Explorer හි විවෘත කරයි."],
            ["Top bar (Activity හිදී)", "<code>Clear history</code>", "Save වූ files මකා නොදමා Activity ලැයිස්තුව පිරිසිදු කරයි."],
            ["Sidebar", "<code>Send</code> / <code>Receive</code> / <code>Activity</code> / <code>Pair devices</code> / <code>Settings</code>", "ප්‍රධාන කොටස් අතර මාරු වීමට; පහළ <code>Sharing is local</code> දැක්වේ."],
            ["Send", "<code>Add files</code> / <code>Add folder</code>", "යැවීමට files හෝ folder එකක් එකතු කරයි."],
            ["Send (staged)", "<code>Clear list</code> / <code>Remove</code>", "ලැයිස්තුව සම්පූර්ණයෙන් හිස් කිරීමට හෝ තනි file එකක් අස් කිරීමට."],
            ["Send", "<code>Quick share with QR</code>", "එක්-වරක් QR මඟින් phone එකකට යැවීමට කවුළුව විවෘත කරයි."],
            ["Quick share QR modal", "<code>Refresh code</code> / <code>Cancel QR share</code>", "අලුත් QR එකක් සෑදීමට හෝ QR share සැසිය නැවැත්වීමට."],
            ["Confirm recipient modal", "<code>Send files</code> / <code>Reject</code>", "Phone එක QR scan කළ පසු යවන්නා අනිවාර්යයෙන් තහවුරු කළ යුතු පණිවිඩය."],
            ["Send → Paired device", "Device card / <code>Pair a new device</code> / <code>Close</code>", "Pair කළ device එකකට QR නොමැතිව යැවීමට."],
            ["Receive", "<code>Start receiving</code> / <code>Stop receiving</code>", "Windows PC එකේ files භාරගැනීම සක්‍රිය/අක්‍රිය කරයි."],
            ["Receive", "<code>Show larger code</code> / <code>Copy receive link</code> / <code>Refresh code</code>", "QR විශාල කිරීමට (<code>Refresh</code> / <code>Copy link</code> / <code>Close</code>), link copy කිරීමට හෝ අලුත් QR සෑදීමට."],
            ["Incoming offer modal", "<code>Accept transfer</code> / <code>Decline</code>", "ලැබෙන files භාරගැනීමට අනුමැතිය දීම හෝ ප්‍රතික්ෂේප කිරීම."],
            ["Pair devices", "<code>Find devices</code> / <code>Show my QR and code</code> / <code>Enter pairing link</code> / <code>Pair by address</code>", "Devices සෙවීම, pair-only QR පෙන්වීම, link paste කිරීම සහ IP:port මඟින් pair වීම."],
            ["Pair devices (actions)", "<code>Copy link</code> / <code>New code</code> / <code>Pair</code> / <code>Approve</code> / <code>Decline</code> / <code>Done</code> / <code>Remove</code>", "Pair-only කේත කළමනාකරණය, අනුමත කිරීම සහ trusted device ඉවත් කිරීම."],
            ["Activity", "<code>Cancel</code> / <code>Search history</code> / <code>All</code> / <code>Sent</code> / <code>Received</code> / <code>Open folder</code> / <code>Technical details</code>", "Transfer නැවැත්වීම, පැරණි වාර්තා සෙවීම, folders විවෘත කිරීම සහ දෝෂ විස්තර බැලීම."],
            ["Settings", "<code>Appearance</code> / <code>Change folder</code> / <code>Advanced connection details</code> / <code>Remove</code>", "Theme වෙනස් කිරීම, destination folder තේරීම, IP/Port බැලීම සහ paired device ඉවත් කිරීම."],
        ]
        lin_hdr = ["තිරය (Screen)", "Control නම", "කාර්යය"]
        lin_rows = [
            ["Navigation", "<code>Send</code> / <code>Receive</code> / <code>Activity</code> / <code>Pair devices</code> / <code>Settings</code>", "Sidebar හරහා පිටු මාරු කිරීමට; පහළ receive තත්ත්වය පෙනේ."],
            ["Send", "<code>Add files...</code> / <code>Add folder...</code> / <code>Clear all</code> / <code>✕</code>", "Files/folders එකතු කිරීම, ලැයිස්තුව හිස් කිරීම සහ තනි file ඉවත් කිරීම."],
            ["Send", "<code>Quick share with QR</code> / <code>Paired device → Connect</code>", "QR share කවුළුව විවෘත කිරීම හෝ paired device එකකට කෙලින්ම යැවීම."],
            ["Quick share / Confirm modals", "<code>Connection details</code> / <code>Refresh code</code> / <code>Cancel quick share</code> / <code>Confirm Recipient</code> / <code>Cancel</code>", "QR පාලනය සහ phone එක scan කළ පසු යවන්නා තහවුරු කිරීම."],
            ["Receive", "<code>Turn on receiving</code> / <code>Turn off receiving</code>", "Linux හි files භාරගැනීම සක්‍රිය/අක්‍රිය කරයි."],
            ["Receive", "<code>Show larger code</code> / <code>Copy receive link</code> / <code>Refresh code</code>", "QR විශාල කිරීම, link copy කිරීම සහ අලුත් කේත සෑදීම."],
            ["Incoming offer modal", "<code>Accept Transfer</code> / <code>Decline</code>", "ලැබෙන files අනුමත කිරීම හෝ ප්‍රතික්ෂේප කිරීම."],
            ["Pair devices", "<code>Find devices</code> / <code>Show my QR and code</code> / <code>Enter pairing link</code> / <code>Pair by address</code>", "Devices සෙවීම, pair-only QR/PIN පෙන්වීම සහ විකල්ප pair ක්‍රම."],
            ["Pair recovery & trust", "<code>Try again</code> / <code>Open Settings</code> / <code>New code</code> / <code>Copy link</code> / <code>Done</code> / <code>Remove</code>", "සෙවීම නැවත කිරීම, නව කේත ගැනීම සහ trusted device ඉවත් කිරීම."],
            ["Activity", "<code>Received files</code> / <code>Clear history</code> / <code>Cancel transfer</code> / <code>Search history</code> / <code>All</code> / <code>Sent</code> / <code>Received</code>", "Folder විවෘත කිරීම, ඉතිහාසය මැකීම, යැවීම නැවැත්වීම සහ වාර්තා පෙරීම."],
            ["Settings", "<code>Appearance</code> / <code>Device Name</code> / <code>Download Directory → Browse...</code> / <code>Revoke selected trusted device</code> / <code>Listening Port</code> / <code>Export diagnostic logs</code>", "Theme, නම, download folder, trusted devices, port සහ diagnostic logs පාලනය."],
        ]
        and_hdr = ["තිරය (Screen)", "Control නම", "කාර්යය"]
        and_rows = [
            ["Home & Tabs", "<code>Send files</code> / <code>Receive files</code> / <code>Pair device</code> / <code>Paired devices</code> / <code>Home</code> / <code>History</code> / <code>Settings</code>", "ප්‍රධාන ක්‍රියා, paired ලැයිස්තුව (<code>Paired</code> / <code>Last seen</code>) සහ පහළ tabs."],
            ["Send selection", "<code>Choose photos &amp; videos</code> / <code>Choose files &amp; documents</code> / <code>Choose folder</code> / <code>Remove</code> / <code>Clear all</code> / <code>Proceed to send</code>", "Phone එකෙන් යැවීමට files තෝරා සැකසීම."],
            ["Recipient & Recovery", "<code>Scanning</code> / <code>Scan QR code or paste invite</code> / <code>Paste invitation</code> / <code>Open Bluetooth settings</code> / <code>Open Wi-Fi settings</code> / <code>Check again</code> / <code>Retry</code> / <code>Cancel</code>", "ළඟ devices තේරීම, QR scan, link paste සහ Bluetooth/Wi-Fi ගැටලු විසඳීම."],
            ["Verification & Offer", "<code>Verify connection</code> (<code>Numbers match</code> / <code>Don't match</code>) / <code>Accept file</code> / <code>Decline</code> / <code>Cancel</code>", "ඉලක්කම් 4 සැසඳීම, ලැබෙන file අනුමැතිය සහ progress නැවැත්වීම."],
            ["Receive readiness & Result", "<code>Ready to receive</code> / <code>Keep receiving in background</code> / <code>Scan PC QR code</code> / <code>Stop receiving</code> / <code>Verified</code> / <code>Share file</code> / <code>Open file</code> / <code>Done</code>", "පසුබිම් ලැබීම, PC QR scan කිරීම සහ සත්‍යාපිත files විවෘත කිරීම."],
            ["History", "Clear trash icon / <code>All</code> / <code>Sent</code> / <code>Received</code>", "ඉතිහාස වාර්තා මැකීම සහ දිශාව අනුව පෙරීම."],
            ["Settings", "<code>Edit</code> / <code>Theme mode</code> / <code>Dynamic color</code> / <code>Reduced motion</code> / <code>Notification details</code> / <code>Keep screen awake</code> / <code>Manage trusted devices</code> / <code>Pair with another device</code> / <code>Delete all</code> / <code>Choose folder</code> / <code>Use Download/Glide</code> / <code>Nearby diagnostics</code> / <code>Privacy policy</code> / <code>Open source licenses</code>", "Phone නම, theme, screen awake, trusted devices, save folder සහ diagnostics."],
        ]
        h_win = "<h3>Windows — සෑම තිරයකම Controls</h3>"
        h_lin = "<h3>Ubuntu / Linux — සෑම තිරයකම Controls</h3>"
        h_and = "<h3>Android — සෑම තිරයකම Controls</h3>"
    else:
        win_hdr = ["Screen", "Control / Label", "Purpose"]
        win_rows = [
            ["Top header", "<code>Received files</code>", "Opens your configured received-files folder in Windows Explorer."],
            ["Top header (Activity)", "<code>Clear history</code>", "Clears history log records without deleting saved files."],
            ["Sidebar", "<code>Send</code> / <code>Receive</code> / <code>Activity</code> / <code>Pair devices</code> / <code>Settings</code> / <code>Sharing is local</code>", "Navigates main views and confirms local-only LAN transfers."],
            ["Send", "<code>Add files</code> / <code>Add folder</code> / <code>Clear list</code> / <code>Remove</code>", "Builds, edits, or clears your queued files batch."],
            ["Send", "<code>Quick share with QR</code> / <code>Paired device</code>", "Starts a one-time phone QR transfer or picks a saved device."],
            ["QR Share & Confirm modals", "<code>Refresh code</code> / <code>Cancel QR share</code> / <code>Confirm receiving device</code> (<code>Send files</code> / <code>Reject</code>)", "Manages temporary QR and lets sender confirm the scanning device before sending."],
            ["Paired device picker", "Saved device row / <code>Pair a new device</code> / <code>Close</code>", "Connects straight to a paired device without rescanning."],
            ["Receive", "<code>Start receiving</code> / <code>Stop receiving</code> / <code>Show larger code</code> / <code>Copy receive link</code> / <code>Refresh code</code>", "Toggles Windows receiving and displays/zooms/rotates the phone transfer QR."],
            ["Large QR & Offer modals", "<code>Refresh</code> / <code>Copy link</code> / <code>Close</code> / <code>Accept transfer</code> / <code>Decline</code>", "Enlarged QR actions plus explicit consent for incoming transfers."],
            ["Pair devices", "<code>Find devices</code> / <code>Show my QR and code</code> / <code>Enter pairing link</code> / <code>Pair by address</code>", "Enrolls trusted peers via discovery, pair-only QR/PIN, link, or IP:port."],
            ["Pair actions", "<code>Copy link</code> / <code>New code</code> / <code>Pair</code> / <code>Approve</code> / <code>Decline</code> / <code>Done</code> / <code>Remove</code>", "Generates invites, completes approval, or revokes a paired peer."],
            ["Activity", "<code>Cancel</code> / <code>Search history</code> / <code>All</code> / <code>Sent</code> / <code>Received</code> / <code>Completed</code> / <code>Open folder</code> / <code>Technical details</code>", "Aborts active transfers, filters verified history, opens folders, inspects errors."],
            ["Settings", "<code>Appearance</code> / <code>Save received files to</code> → <code>Change folder</code> / <code>Advanced connection details</code> / <code>Trusted devices → Remove</code>", "Sets theme, destination path, local IP/port/fingerprint info, and revokes trust."],
        ]
        lin_hdr = ["Screen", "Control / Label", "Purpose"]
        lin_rows = [
            ["Sidebar", "<code>Send</code> / <code>Receive</code> / <code>Activity</code> / <code>Pair devices</code> / <code>Settings</code> / Receive status banner", "Navigates views and shows live Ready to receive / paused status."],
            ["Send", "<code>Add files...</code> / <code>Add folder...</code> / <code>Clear all</code> / <code>✕</code> / <code>Quick share with QR</code> / <code>Paired device → Connect</code>", "Queues items, clears all or one entry, starts QR share or direct connect."],
            ["QR & Confirm modals", "<code>Connection details</code> / <code>Refresh code</code> / <code>Cancel quick share</code> / <code>Confirm Recipient</code> / <code>Cancel</code>", "Inspects endpoints, refreshes/cancels QR share, confirms scanning recipient."],
            ["Receive", "<code>Turn on receiving</code> / <code>Turn off receiving</code> / <code>Show larger code</code> / <code>Copy receive link</code> / <code>Refresh code</code>", "Toggles Linux listener (different label from Windows) and phone transfer QR."],
            ["Incoming offer modal", "<code>Accept Transfer</code> / <code>Decline</code>", "Explicit consent prompt before saving into staging."],
            ["Pair devices", "<code>Find devices</code> / <code>Show my QR and code</code> / <code>Enter pairing link</code> / <code>Pair by address</code>", "Four ways to discover and pair trusted desktops or phones."],
            ["Pair actions & recovery", "<code>Copy link</code> / <code>New code</code> / <code>Try again</code> / <code>Open Settings</code> / <code>Pair</code> / <code>Done</code> / <code>Remove</code> / <code>Connection details</code>", "Manages invites, retries failed discovery, and removes saved peers."],
            ["Activity", "<code>Received files</code> / <code>Clear history</code> / <code>Cancel transfer</code> / <code>Search history</code> / <code>All</code> / <code>Sent</code> / <code>Received</code> / <code>Completed</code>", "Opens download folder, cancels active transfers, searches verified history."],
            ["Settings", "<code>Appearance</code> / <code>Device Name</code> / <code>Download Directory → Browse...</code> / <code>Trusted Devices Details</code> / <code>Revoke selected trusted device</code> / <code>Listening Port</code> / <code>Export diagnostic logs</code>", "Configures theme, host name, save folder, saved devices, TCP port, diagnostics."],
        ]
        and_hdr = ["Screen", "Control / Label", "Purpose"]
        and_rows = [
            ["Home & Navigation", "<code>Send files</code> / <code>Receive files</code> / <code>Pair device</code> / <code>Paired devices</code> (<code>Paired</code>, <code>Last seen</code>) / <code>Home</code> / <code>History</code> / <code>Settings</code>", "Starts send/receive/pair flows, displays saved desktop peers, switches bottom tabs."],
            ["Send selection", "<code>Choose photos &amp; videos</code> / <code>Choose files &amp; documents</code> / <code>Choose folder</code> / <code>Remove</code> / <code>Clear all</code> / <code>Proceed to send</code>", "Stages photos, documents, or folders and edits the send batch."],
            ["Recipient & Recovery", "<code>Scanning</code> / <code>Scan QR code or paste invite</code> / <code>Paste invitation</code> / <code>Open Bluetooth settings</code> / <code>Open Wi-Fi settings</code> / <code>Check again</code> / <code>Retry</code> / <code>Cancel</code>", "Selects phones or desktop QRs, pastes glide:// links, recovers radios."],
            ["Verification & Offer", "<code>Verify connection</code> (<code>Numbers match</code> / <code>Don't match</code>) / <code>Accept file</code> / <code>Decline</code> / <code>Cancel</code>", "Verifies 4-digit SAS codes, accepts incoming files, aborts active transfers."],
            ["Receive & Completion", "<code>Ready to receive</code> / <code>Keep receiving in background</code> / <code>Scan PC QR code</code> / <code>Stop receiving</code> / <code>Verified</code> / <code>Share file</code> / <code>Open file</code> / <code>Done</code>", "Controls phone listening/background service and opens/shares verified downloads."],
            ["History", "Clear history trash icon / <code>All</code> / <code>Sent</code> / <code>Received</code> / Session cards", "Filters transfer log and lets you re-open or share verified items."],
            ["Settings", "<code>Edit</code> / <code>Theme mode</code> / <code>Dynamic color</code> / <code>Reduced motion</code> / <code>Notification details</code> / <code>Keep screen awake</code> / <code>Manage trusted devices</code> / <code>Pair with another device</code> / <code>Delete all</code> / <code>Choose folder</code> / <code>Use Download/Glide</code> / <code>Nearby diagnostics</code> / <code>Privacy policy</code> / <code>Open source licenses</code>", "Renames device, sets theme & wake lock, manages trust/folder, runs diagnostics."],
        ]
        h_win = "<h3>Windows — Every control, every screen</h3>"
        h_lin = "<h3>Ubuntu / Linux — Every control, every screen</h3>"
        h_and = "<h3>Android — Every control, every screen</h3>"

    return (
        h_win
        + _table_html(win_hdr, win_rows, si)
        + h_lin
        + _table_html(lin_hdr, lin_rows, si)
        + h_and
        + _table_html(and_hdr, and_rows, si)
    )


CSS = """
:root {
  --bg: #0c1426;
  --surface: #14213b;
  --surface-2: #192841;
  --border: #324360;
  --text: #f4f7ff;
  --text-muted: #c2cfe8;
  --accent: #5f86ed;
  --link: #9ec0ff;
  --code-bg: #1f3358;
  --warn-bg: #322414;
  --warn-border: #d97706;
  --tbl-alt: #132038;
}
@media (prefers-color-scheme: light) {
  :root:not([data-theme="dark"]) {
    --bg: #f4f7fc;
    --surface: #ffffff;
    --surface-2: #ffffff;
    --border: #cbd5e1;
    --text: #0f172a;
    --text-muted: #475569;
    --accent: #2563eb;
    --link: #1d4ed8;
    --code-bg: #e2e8f0;
    --warn-bg: #fef3c7;
    --warn-border: #d97706;
    --tbl-alt: #f8fafc;
  }
}
:root[data-theme="light"] {
  --bg: #f4f7fc;
  --surface: #ffffff;
  --surface-2: #ffffff;
  --border: #cbd5e1;
  --text: #0f172a;
  --text-muted: #475569;
  --accent: #2563eb;
  --link: #1d4ed8;
  --code-bg: #e2e8f0;
  --warn-bg: #fef3c7;
  --warn-border: #d97706;
  --tbl-alt: #f8fafc;
}
:root[data-theme="dark"] {
  --bg: #0c1426;
  --surface: #14213b;
  --surface-2: #192841;
  --border: #324360;
  --text: #f4f7ff;
  --text-muted: #c2cfe8;
  --accent: #5f86ed;
  --link: #9ec0ff;
  --code-bg: #1f3358;
  --warn-bg: #322414;
  --warn-border: #d97706;
  --tbl-alt: #132038;
}
*, *::before, *::after { box-sizing: border-box; }
html { scroll-behavior: smooth; }
body {
  margin: 0;
  background: var(--bg);
  color: var(--text);
  font: 16px/1.62 system-ui, "Segoe UI", "Nirmala UI", "Noto Sans Sinhala", Arial, sans-serif;
}
a { color: var(--link); }
:focus-visible {
  outline: 3px solid var(--accent);
  outline-offset: 3px;
  border-radius: 4px;
}
code {
  background: var(--code-bg);
  padding: 0.1rem 0.38rem;
  border-radius: 5px;
  font-size: 0.9em;
  font-family: ui-monospace, Consolas, "Segoe UI Mono", monospace;
}
header.site-header {
  background: var(--surface);
  border-bottom: 3px solid var(--accent);
  padding: 1.75rem max(20px, calc((100vw - 1340px) / 2));
}
.header-row {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  align-items: flex-start;
  gap: 1rem;
}
.header-controls {
  display: flex;
  flex-wrap: wrap;
  gap: 0.65rem;
  align-items: center;
}
.pill-btn, .theme-toggle {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
  padding: 0.45rem 0.85rem;
  border-radius: 999px;
  border: 1px solid var(--border);
  background: var(--surface-2);
  color: var(--text);
  text-decoration: none;
  font-size: 0.9rem;
  font-weight: 600;
  cursor: pointer;
}
.pill-btn:hover, .theme-toggle:hover { border-color: var(--accent); }
h1 { margin: 0.15rem 0 0.4rem; font-size: clamp(1.5rem, 2.5vw, 2.05rem); }
.subtitle { margin: 0; color: var(--text-muted); max-width: 78ch; }
.layout {
  max-width: 1340px;
  margin: 0 auto;
  padding: 1.5rem 20px 4rem;
}
.toc-desktop { display: none; }
.toc-mobile {
  background: var(--surface-2);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 0.75rem 1rem;
  margin-bottom: 1.25rem;
}
.toc-mobile summary { font-weight: 700; cursor: pointer; }
.toc-list {
  list-style: none;
  padding: 0.4rem 0 0;
  margin: 0;
  display: grid;
  gap: 0.3rem;
}
.toc-list a {
  display: block;
  padding: 0.25rem 0.45rem;
  border-radius: 6px;
  text-decoration: none;
  font-size: 0.94rem;
}
.toc-list a:hover { background: var(--code-bg); }
@media (min-width: 1180px) {
  .toc-mobile { display: none; }
  .layout {
    display: grid;
    grid-template-columns: 270px minmax(0, 1fr);
    gap: 2rem;
    align-items: start;
  }
  .toc-desktop {
    display: block;
    position: sticky;
    top: 1.25rem;
    background: var(--surface-2);
    border: 1px solid var(--border);
    border-radius: 12px;
    padding: 1rem;
    max-height: calc(100vh - 2.5rem);
    overflow-y: auto;
  }
  .toc-desktop h2 {
    margin: 0 0 0.5rem;
    font-size: 1rem;
    color: var(--text);
  }
}
main { min-width: 0; }
section {
  border-bottom: 1px solid var(--border);
  padding: 1rem 0 2.25rem;
}
h2 { color: var(--link); margin-top: 0.5rem; }
h3 { margin-top: 1.4rem; }
.back-top { margin-top: 1.5rem; font-size: 0.9rem; text-align: right; }
.callout-card {
  background: var(--surface-2);
  border: 1px solid var(--border);
  border-left: 4px solid var(--accent);
  border-radius: 10px;
  padding: 0.9rem 1.2rem;
  margin: 1rem 0;
}
.callout-card.warn {
  background: var(--warn-bg);
  border-left-color: var(--warn-border);
}
.callout-card h3 { margin-top: 0.1rem; }
.fig-desktop {
  display: block;
  width: min(100%, 1000px);
  margin: 1.25rem 0;
  padding: 14px;
  background: var(--surface-2);
  border: 1px solid var(--border);
  border-radius: 12px;
}
.fig-pair {
  display: flex;
  flex-wrap: wrap;
  gap: 1.25rem;
  align-items: flex-start;
  margin: 1.15rem 0;
}
.fig-phone {
  display: block;
  width: min(100%, 440px);
  margin: 0;
  padding: 12px;
  background: var(--surface-2);
  border: 1px solid var(--border);
  border-radius: 12px;
}
.zoom-trigger {
  position: relative;
  display: block;
  width: 100%;
  padding: 0;
  border: 0;
  background: transparent;
  cursor: zoom-in;
  border-radius: 8px;
  overflow: hidden;
}
.fig-desktop img {
  display: block;
  width: 100%;
  height: auto;
  border-radius: 6px;
}
.fig-phone img {
  display: block;
  width: min(100%, 380px);
  margin: 0 auto;
  height: auto;
  border-radius: 8px;
}
.zoom-hint {
  position: absolute;
  right: 8px;
  bottom: 8px;
  background: rgba(12, 20, 38, 0.85);
  color: #fff;
  font-size: 0.78rem;
  padding: 2px 8px;
  border-radius: 6px;
  border: 1px solid rgba(255, 255, 255, 0.28);
}
figcaption { margin-top: 0.75rem; font-size: 0.92rem; }
figcaption strong { display: block; margin-bottom: 0.5rem; font-size: 0.98rem; }
.callout-tbl, table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.88rem;
  margin-top: 0.45rem;
}
.tbl-wrap { overflow-x: auto; margin: 0.75rem 0 1.25rem; }
th, td {
  border: 1px solid var(--border);
  padding: 0.42rem 0.58rem;
  text-align: left;
  vertical-align: top;
}
th { background: var(--code-bg); font-weight: 700; }
tbody tr:nth-child(even) td { background: var(--tbl-alt); }
.c-num { width: 48px; text-align: center; }
.c-lbl { width: 220px; }
.badge-pill {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 24px;
  height: 24px;
  padding: 0 6px;
  border-radius: 999px;
  background: var(--accent);
  color: #fff;
  font-weight: 700;
  font-size: 0.82rem;
}
.route-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(290px, 1fr));
  gap: 1rem;
  margin: 1rem 0;
}
.route {
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 0.9rem 1.1rem;
  background: var(--surface-2);
}
.route h3 { margin-top: 0; font-size: 1.05rem; }
.trouble-list dt, .glossary-list dt { font-weight: 700; margin-top: 1rem; font-size: 1.02rem; }
.trouble-list dd, .glossary-list dd { margin: 0.25rem 0 0 0; color: var(--text-muted); }
.faq-item {
  background: var(--surface-2);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 0.7rem 1rem;
  margin: 0.65rem 0;
}
.faq-item summary { font-weight: 700; cursor: pointer; }
.faq-item p { margin: 0.6rem 0 0.1rem; }
footer.site-footer {
  padding: 2rem 20px;
  text-align: center;
  color: var(--text-muted);
  border-top: 1px solid var(--border);
}
dialog#lightbox {
  max-width: 96vw;
  max-height: 94vh;
  padding: 0.85rem;
  border: 1px solid var(--border);
  border-radius: 12px;
  background: var(--surface);
  color: var(--text);
}
dialog#lightbox::backdrop { background: rgba(6, 10, 20, 0.82); }
.lb-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 1rem;
  margin-bottom: 0.6rem;
}
.lb-close {
  padding: 0.35rem 0.8rem;
  border-radius: 8px;
  border: 1px solid var(--border);
  background: var(--accent);
  color: #fff;
  font-weight: 700;
  cursor: pointer;
}
#lightbox img {
  display: block;
  max-width: 91vw;
  max-height: 82vh;
  width: auto;
  height: auto;
  margin: 0 auto;
}
@media print {
  :root {
    --bg: #ffffff;
    --surface: #ffffff;
    --surface-2: #ffffff;
    --border: #b8c2d0;
    --text: #111827;
    --text-muted: #334155;
    --accent: #1d4ed8;
    --link: #1d4ed8;
    --code-bg: #eef2f7;
    --warn-bg: #fffbeb;
    --warn-border: #b45309;
    --tbl-alt: #f8fafc;
  }
  body { background: #fff !important; color: #111827 !important; font-size: 10.5pt; }
  header.site-header { padding: 0 0 0.6rem; border-bottom-width: 2px; }
  .layout { display: block; padding: 0; max-width: none; }
  .toc-desktop, .toc-mobile, .back-top, .header-controls, .zoom-hint, #lightbox { display: none !important; }
  section { border-bottom: 1px solid #d1d5db; padding: 0.6rem 0 1.2rem; }
  h2 { break-after: avoid; color: #17366b; }
  h3 { break-after: avoid; }
  .fig-desktop { width: 100%; max-width: 100%; break-inside: avoid; page-break-inside: avoid; padding: 8px; }
  .fig-desktop img { max-height: 11.5cm; object-fit: contain; }
  .fig-pair { display: flex; flex-wrap: wrap; gap: 0.75rem; }
  .fig-phone { width: 48%; max-width: 8.8cm; break-inside: avoid; page-break-inside: avoid; padding: 8px; }
  .fig-phone img { max-height: 9.8cm; object-fit: contain; width: auto; max-width: 100%; }
  .route, .callout-card, .faq-item, tr { break-inside: avoid; page-break-inside: avoid; }
  .faq-item[open] { display: block; }
  a { color: #17366b; text-decoration: none; }
}
"""

JS = """
(function () {
  var root = document.documentElement;
  var toggle = document.getElementById('theme-toggle');
  var lb = document.getElementById('lightbox');
  var lbImg = document.getElementById('lb-img');
  var lbCap = document.getElementById('lb-cap');
  var lbClose = document.getElementById('lb-close');

  function currentEffectiveTheme() {
    var attr = root.getAttribute('data-theme');
    if (attr === 'light' || attr === 'dark') return attr;
    return window.matchMedia && window.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark';
  }

  function applyTheme(t) {
    root.setAttribute('data-theme', t);
    try { localStorage.setItem('glide-guide-theme', t); } catch (e) {}
    if (toggle) toggle.setAttribute('aria-pressed', String(t === 'light'));
  }

  try {
    var saved = localStorage.getItem('glide-guide-theme');
    if (saved === 'light' || saved === 'dark') applyTheme(saved);
  } catch (e) {}

  if (toggle) {
    toggle.addEventListener('click', function () {
      applyTheme(currentEffectiveTheme() === 'light' ? 'dark' : 'light');
    });
  }

  document.addEventListener('click', function (ev) {
    var btn = ev.target && ev.target.closest ? ev.target.closest('.zoom-trigger') : null;
    if (!btn || !lb || !lbImg || !lbCap) return;
    var img = btn.querySelector('img');
    if (!img) return;
    lbImg.src = img.getAttribute('src');
    lbImg.alt = img.getAttribute('alt') || '';
    lbCap.textContent = btn.getAttribute('data-caption') || '';
    if (typeof lb.showModal === 'function') lb.showModal();
  });

  if (lbClose && lb) {
    lbClose.addEventListener('click', function () { lb.close(); });
    lb.addEventListener('click', function (ev) {
      if (ev.target === lb) lb.close();
    });
  }
})();
"""


def make_html(si: bool, used_collector: set[tuple[str, str]] | None = None) -> str:
    lang = "si" if si else "en"
    title = "Glide 1.0.0 භාවිත මාර්ගෝපදේශය (සිංහල + English)" if si else "Glide 1.0.0 Offline User Guide"
    intro = (
        "එකම local Wi-Fi / LAN ජාලයේ උපාංග අතර අන්තර්ජාලයෙන් තොරව files යවන්න. සියලුම බොත්තම් සහ tab නම් යෙදුමේ පෙනෙන English නාමයෙන්ම දක්වා ඇත."
        if si
        else "Transfer files directly between devices on your local network without internet or cloud accounts. Every screenshot, badge, and label matches the installed Glide 1.0.0 interface."
    )
    labels = TITLES["si"] if si else TITLES["en"]
    used: set[tuple[str, str]] = used_collector if used_collector is not None else set()

    bodies = [
        _section_1_quickstart(si, used),
        _section_2_screens(si, used),
        _section_3_pairing(si, used),
        _section_4_send_receive(si, used),
        _section_5_received_files(si, used),
        _section_6_activity(si, used),
        _section_7_settings(si, used),
        _section_8_troubleshooting(si, used),
        _section_9_faq_limits_glossary(si),
        _section_10_appendix(si),
    ]
    sections_html = "".join(
        section(i, lbl, body, si) for i, (lbl, body) in enumerate(zip(labels, bodies), 1)
    )

    toc_items = "".join(
        f'<li><a href="#s{i}">{i}. {html.escape(n)}</a></li>'
        for i, n in enumerate(labels, 1)
    )
    toc_heading = "පටුන (Contents)" if si else "Contents"
    toggle_label = "වර්ණය (Light / Dark)" if si else "Theme (Light / Dark)"
    close_label = "වසන්න (Close)" if si else "Close"
    footer_txt = (
        "Glide 1.0.0 · 2026-11-12 · Offline පරිශීලක මාර්ගෝපදේශය · අන්තර්ජාලය හෝ ගිණුම් අවශ්‍ය නොවේ · "
        "<a href=\"START-HERE.html\">Start page</a>"
        if si
        else "Glide 1.0.0 · 2026-11-12 · Offline User Guide · Zero cloud, zero internet required · "
        "<a href=\"START-HERE.html\">Start page</a>"
    )

    return (
        f'<!doctype html><html lang="{lang}">'
        f'<head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">'
        f'<title>{html.escape(title)}</title><style>{CSS}</style></head>'
        f'<body id="top">'
        f'<header class="site-header"><div class="header-row"><div>'
        f'<h1>{html.escape(title)}</h1>'
        f'<p class="subtitle">{html.escape(intro)}</p>'
        f'</div><div class="header-controls">'
        f'<a class="pill-btn" href="guide-en.html">English</a>'
        f'<a class="pill-btn" href="guide-si.html">සිංහල + English</a>'
        f'<button type="button" id="theme-toggle" class="theme-toggle" aria-pressed="false">{html.escape(toggle_label)}</button>'
        f'</div></div></header>'
        f'<div class="layout">'
        f'<aside class="toc-desktop" aria-label="{html.escape(toc_heading)}">'
        f'<h2>{html.escape(toc_heading)}</h2><ol class="toc-list">{toc_items}</ol></aside>'
        f'<div>'
        f'<details class="toc-mobile"><summary>{html.escape(toc_heading)}</summary><ol class="toc-list">{toc_items}</ol></details>'
        f'<main>{sections_html}</main>'
        f'</div></div>'
        f'<footer class="site-footer">{footer_txt}</footer>'
        f'<dialog id="lightbox" aria-labelledby="lb-cap">'
        f'<div class="lb-bar"><strong id="lb-cap"></strong>'
        f'<button type="button" id="lb-close" class="lb-close">{html.escape(close_label)}</button></div>'
        f'<img id="lb-img" src="" alt="">'
        f'</dialog>'
        f'<script>{JS}</script>'
        f'</body></html>'
    )


def verify_build(manifest: list[dict[str, Any]], en_html: str, si_html: str, used_en: set[tuple[str, str]], used_si: set[tuple[str, str]]) -> None:
    # 1. Verify all 50 unique screenshots have CALLOUT_SPECS and were used in both editions
    unique_manifest = [e for e in manifest if e.get("duplicate_of") is None]
    expected_keys = {(e["platform"].lower(), e["state"]) for e in unique_manifest}
    missing_en = expected_keys - used_en
    missing_si = expected_keys - used_si
    if missing_en or missing_si:
        raise RuntimeError(f"Unused screenshots! missing_en={missing_en}, missing_si={missing_si}")

    # 2. Verify every referenced asset exists and originals were untouched
    for e in manifest:
        pub = ROOT / e["published"]
        if not pub.exists() or sha(pub) != e["published_sha256"]:
            raise RuntimeError(f"Original asset integrity check failed: {pub}")
        ann = e.get("annotated")
        if not ann or not (ROOT / ann).exists():
            raise RuntimeError(f"Annotated asset missing for {e['renamed']}: {ann}")

    # 3. Verify EN/SI structural parity (exact section count & figure count)
    en_secs = len(re.findall(r'<section id="s\d+">', en_html))
    si_secs = len(re.findall(r'<section id="s\d+">', si_html))
    if en_secs != si_secs or en_secs != len(TITLES["en"]):
        raise RuntimeError(f"Section parity mismatch: EN={en_secs}, SI={si_secs}")

    en_figs = len(re.findall(r"<figure\b", en_html))
    si_figs = len(re.findall(r"<figure\b", si_html))
    if en_figs != si_figs or en_figs != len(unique_manifest):
        raise RuntimeError(f"Figure count mismatch: EN={en_figs}, SI={si_figs}, unique={len(unique_manifest)}")

    # 4. Verify Sinhala coverage on si strings & purity of EN strings
    for lbl in TITLES["si"]:
        if lbl != "Settings" and not has_sinhala(lbl):
            raise RuntimeError(f"SI section title missing Sinhala: {lbl!r}")
    for key, spec in CALLOUT_SPECS.items():
        for fld in ("title_en", "alt_en"):
            if has_sinhala(spec[fld]):
                raise RuntimeError(f"EN {fld} contains Sinhala characters on {key}: {spec[fld]!r}")
        for fld in ("title_si", "alt_si"):
            if not has_sinhala(spec[fld]):
                raise RuntimeError(f"SI {fld} missing Sinhala on {key}: {spec[fld]!r}")
        for c in spec["callouts"]:
            if has_sinhala(c["label_en"]) or has_sinhala(c["desc_en"]):
                raise RuntimeError(f"EN callout #{c['n']} contains Sinhala on {key}")
            if not has_sinhala(c["desc_si"]):
                raise RuntimeError(f"SI callout #{c['n']} desc_si missing Sinhala on {key}: {c['desc_si']!r}")


def build_all(source_dir: Path | None = None) -> tuple[list[dict[str, Any]], set[tuple[str, str]]]:
    manifest = prepare_assets(source_dir)
    used_en: set[tuple[str, str]] = set()
    used_si: set[tuple[str, str]] = set()
    en_html = make_html(False, used_en)
    si_html = make_html(True, used_si)
    verify_build(manifest, en_html, si_html, used_en, used_si)
    (ROOT / "guide-en.html").write_text(en_html, encoding="utf-8")
    (ROOT / "guide-si.html").write_text(si_html, encoding="utf-8")
    return manifest, used_en


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Generate annotated Glide user guide images and offline EN/SI HTML guides."
    )
    parser.add_argument(
        "--source",
        type=Path,
        default=None,
        help="Optional folder containing Windows/, Linux/, Android/ raw screenshots to copy non-destructively into assets/.",
    )
    args = parser.parse_args()
    manifest, used = build_all(args.source)
    unique_cnt = sum(1 for e in manifest if e.get("duplicate_of") is None)
    print(
        f"Built {unique_cnt}/{unique_cnt} annotated images, updated screenshot-manifest.json ({len(manifest)} entries), "
        f"and wrote guide-en.html + guide-si.html ({len(used)} figures each)."
    )


if __name__ == "__main__":
    main()
