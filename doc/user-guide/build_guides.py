"""Build the offline Glide guides from reviewed, locally supplied screenshots."""
from __future__ import annotations

import hashlib
import html
import json
import shutil
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parent
SOURCE = Path(r"C:\Users\GDJ\Pictures\Screenshots\Glide UI")
BACKUP = SOURCE / "_originals"
ASSETS = ROOT / "assets"

# Actual screen/state names observed in the supplied 1.0.0 screenshots.
SLUGS = {
 "Windows": ["send-empty", "receive-ready", "activity-history", "pair-find", "pair-enter-code", "pair-complete", "pair-show-qr", "pair-enter-link", "pair-by-address", "settings", "send-qr-dialog", "send-paired-picker", "receive-large-qr", "pair-approval", "receive-offer", "activity-cancelled", "activity-receiving"],
 "Linux": ["receive-ready", "send-empty", "activity-history", "pair-find", "pair-show-qr", "pair-enter-link", "pair-by-address", "settings-upper", "settings-lower", "pair-not-found", "pair-enter-code", "pair-complete", "send-qr-dialog", "send-staged", "activity-sending", "receive-offer"],
 "Android": ["home", "send-select-empty", "send-select-staged", "recipient-bluetooth-off", "recipient-wifi-off", "recipient-no-devices", "send-qr-confirmation", "send-progress", "nearby-verify", "receive-offer", "receive-complete", "recipient-list", "nearby-verify-light", "history", "settings-appearance", "settings-trust", "settings-storage", "recipient-list-duplicate", "nearby-verify-light-duplicate"],
}

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def prepare_assets():
    manifest=[]
    for platform, slugs in SLUGS.items():
        p=SOURCE/platform; backup=BACKUP/platform; backup.mkdir(parents=True,exist_ok=True)
        files=sorted([x for x in p.iterdir() if x.suffix.lower() in {".png",".jpg",".jpeg"}])
        # On subsequent builds the original names live in the private backup.
        for x in files:
            if not x.stem.startswith(platform.lower()+"-"):
                b=backup/x.name
                if not b.exists(): shutil.copy2(x,b)
                if sha(x)!=sha(b): raise RuntimeError(f"Backup mismatch: {x}")
                x.unlink()  # Original is retained unchanged in the private backup.
        originals=sorted([x for x in backup.iterdir() if x.suffix.lower() in {".png",".jpg",".jpeg"} and not x.stem.startswith(platform.lower()+"-")])
        if len(originals)!=len(slugs): raise RuntimeError(f"{platform}: expected {len(slugs)} originals, found {len(originals)}")
        out=ASSETS/platform.lower();out.mkdir(parents=True,exist_ok=True)
        seen_by_hash={}
        for source,slug in zip(originals,slugs):
            ext=".png" if platform!="Android" else ".jpg"
            new=f"{platform.lower()}-{slug}-dark{ext}"
            user_copy=p/new
            if not user_copy.exists(): shutil.copy2(source,user_copy)
            if sha(user_copy)!=sha(source): raise RuntimeError(f"Copy mismatch: {new}")
            im=Image.open(source)
            target=out/new
            digest=sha(source)
            if digest in seen_by_hash:
                if target.exists(): target.unlink()
                published=seen_by_hash[digest]
            else:
                shutil.copy2(source,target)
                published=target
                seen_by_hash[digest]=target
            manifest.append(dict(platform=platform,original=source.name,renamed=new,state=slug,theme="light" if slug in {"nearby-verify-light","nearby-verify-light-duplicate","recipient-list-duplicate"} else "dark",dimensions=list(im.size),sha256=digest,published_sha256=sha(published),duplicate_of=None if published==target else published.name,redaction_regions=[],published=str(published.relative_to(ROOT)).replace('\\','/')))
    (ROOT/"screenshot-manifest.json").write_text(json.dumps(manifest,indent=2,ensure_ascii=False),encoding="utf-8")
    return manifest

def fig(platform,slug,caption,controls):
    name=f"{platform}-{slug}-dark."+("jpg" if platform=="android" else "png")
    if slug=="nearby-verify-light": name="android-nearby-verify-light-dark.jpg"
    return f'<figure><img src="assets/{platform}/{name}" alt="{html.escape(caption)}"><figcaption><strong>{html.escape(caption)}</strong><br>{html.escape(controls)}</figcaption></figure>'

def section(i,title,body):return f'<section id="s{i}"><h2>{i}. {title}</h2>{body}</section>'

ROUTES=[("Windows","Windows"),("Windows","Linux"),("Windows","Android"),("Linux","Linux"),("Linux","Windows"),("Linux","Android"),("Android","Android"),("Android","Windows"),("Android","Linux")]

def route_card(a,b,si):
    if si:
        select=("Home → Send files → Choose files & documents / Choose folder → Proceed to send." if a=="Android" else "Send → Add files / Add folder → Paired device තෝරන්න.")
        recv=("Glide open තබන්න (Home, History, Settings හෝ Receive files). Offer එක පෙන්වූ විට Accept file ඔබන්න." if b=="Android" else "Receive → Turn on receiving; offer එකේ Accept transfer ඔබන්න.")
        if a=="Android" and b=="Android": connect="Select recipient තුළ ලැබෙන phone එක තෝරන්න; දෙපස Verify connection අංක සසඳා Numbers match තෝරන්න."
        elif a=="Android": connect="Desktop එකේ Receive හි transfer QR පෙන්වන්න. Phone එකෙන් එය scan කරන්න හෝ invitation link paste කරන්න."
        elif b=="Android": connect="Paired device තුළ phone එක තෝරා Connect ඔබන්න. Phone එකේ Glide open තබන්න; නැවත QR scan කිරීම අවශ්‍ය නැත. ලැබෙන offer එක Accept file මඟින් අනුමත කරන්න."
        else: connect="Paired device තුළ ලැබෙන desktop එක තෝරා Connect ඔබන්න. Pair කර නැත්නම් මුලින් Pair devices පිටුවේ pair කරන්න."
        finish=("Transfer complete හි Verified බලන්න; file එක Download/Glide හෝ Settings හි තෝරාගත් folder එකට save වේ." if b=="Android" else "Activity හි Transfer complete සහ verified count බලන්න; Settings හි තෝරාගත් destination එක හෝ Open folder භාවිතා කරන්න.")
    else:
        select=("Home → Send files → Choose files & documents or Choose folder → Proceed to send." if a=="Android" else "Send → Add files or Add folder → Paired device.")
        recv=("Keep Glide open on Home, History, Settings, or Receive files. Tap Accept file when the offer appears." if b=="Android" else "Receive → Turn on receiving; tap Accept transfer on the incoming offer.")
        if a=="Android" and b=="Android": connect="In Select recipient, choose the other phone; compare Verify connection numbers on both devices and tap Numbers match only when they agree."
        elif a=="Android": connect="On the desktop, show the transfer QR in Receive. Scan it on the phone or paste its receive invitation link."
        elif b=="Android": connect="Under Paired device, choose the saved phone and select Connect. Keep Glide open on the phone; no new QR scan is needed. Approve the incoming offer with Accept file."
        else: connect="Choose the receiving desktop under Paired device and tap Connect. If it is not saved yet, first use Pair devices on both desktops."
        finish=("Check Transfer complete and Verified. The file saves to Download/Glide or the folder selected in Settings." if b=="Android" else "Check Transfer complete and verified count in Activity; use Open folder or the destination shown in Settings.")
    actor=("යවන්නා" if si else "Sender"); dest=("ලබන්නා" if si else "Receiver")
    return f'<article class="route"><h3>{a} → {b}</h3><ol><li><b>{actor}:</b> {select}</li><li><b>{dest}:</b> {recv}</li><li><b>{actor}:</b> {connect}</li><li><b>{dest}:</b> {finish}</li></ol></article>'

def make_html(si):
    title="Glide භාවිත මාර්ගෝපදේශය" if si else "Glide User Guide"
    intro=("Glide හරහා local network එකේ devices අතර files යවන්න. Button නම් application එකේ පෙනෙන English ආකාරයෙන්ම දක්වා ඇත." if si else "Share files between devices on your local network. Control names match the installed Glide 1.0.0 interface.")
    labels=(['ආරම්භය','තිර හඳුනාගැනීම','Device pair කිරීම','Files යැවීම සහ ලැබීම','ලැබුණු files සොයාගැනීම','Activity සහ History','Settings','ගැටලු විසඳීම'] if si else ['Getting started','Screen guide','Pair devices','Send and receive','Find received files','Activity and History','Settings','Troubleshooting'])
    b=[]
    b.append(section(1,labels[0],('<p>Windows, Ubuntu/Linux සහ Android සඳහා Glide 1.0.0 install කර එකම local network එකට සම්බන්ධ කරන්න. Desktop හි Receive → Turn on receiving ඔබන්න. Android හි camera, Nearby, file access අවසර ඉල්ලුවොත් එම කාර්යයට අවශ්‍ය අවසර ලබා දෙන්න. අන්තර්ජාලය හෝ account එකක් අවශ්‍ය නැත.</p>' if si else '<p>Install Glide 1.0.0 on Windows, Ubuntu/Linux, or Android. Connect both devices to the same local network. On desktop, open Receive and choose Turn on receiving. On Android, grant camera, Nearby, or file access when prompted for the action you chose. No account or internet service is required for local sharing.</p>')+fig('windows','send-empty','Windows Send','1 Send: choose files; 2 Quick share with QR: one-time transfer; 3 Paired device: saved identity.')+fig('android','home','Android Home','1 Send files; 2 Receive files; 3 Pair device; 4 History and Settings in bottom navigation.')))
    b.append(section(2,labels[1],('<p>Desktop sidebar එකේ Send, Receive, Activity, Pair devices, Settings තිබේ. Android Home තුළ Send files, Receive files, Pair device සහ පහළ Home, History, Settings තිබේ. Screens අතර controls වෙනස් විය හැක; මෙහි දැක්වෙන්නේ ඒ platform එකේ නියම UI එකයි.</p>' if si else '<p>The desktop sidebar has Send, Receive, Activity, Pair devices, and Settings. Android Home has Send files, Receive files, Pair device, plus Home, History, and Settings along the bottom. Use the controls on your platform.</p>')+fig('linux','send-empty','Linux Send','1 Add files / Add folder; 2 Quick share with QR; 3 Paired device.')+fig('windows','receive-ready','Windows Receive','1 Turn on/off receiving; 2 Transfer QR; 3 Show larger code; 4 Copy receive link; 5 Refresh code.')+fig('linux','receive-ready','Linux Receive','1 Turn off receiving; 2 Connect a phone; 3 Show larger code; 4 Copy receive link; 5 Refresh code.')))
    pair_text=('<p><b>Pair devices</b> යනු future connections සඳහා trusted identity එක save කිරීමයි. මෙයින් files යවන්නේ නැත. Desktop දෙකක: එකක Show my QR and code තෝරන්න; අනෙකේ Find devices, device එක තෝරා පෙන්වන six-digit code එක ඇතුළු කරන්න. දෙපස approval සම්පූර්ණ කරන්න. Discovery අසාර්ථක නම් Pair by address හි local address සහ code ඇතුළු කරන්න. Phone එකෙන් pair කිරීමට Pair device මඟින් desktop pair-only QR scan කරන්න. Enter pairing link යොදා current invitation එක paste කළ හැක. Code කල් ඉකුත් වුවහොත් New code භාවිතා කරන්න.</p>' if si else '<p><b>Pair devices</b> saves a trusted identity for later connections; it does not send files. Between desktops, choose Show my QR and code on one device. On the other, Find devices, select it, enter the displayed six-digit code, and approve on both devices. If discovery fails, use Pair by address with the local address and code. To pair a phone, use Pair device to scan the desktop pair-only QR. Enter pairing link accepts a current invitation. Use New code after expiry.</p>')
    b.append(section(3,labels[2],pair_text+fig('windows','pair-find','Windows pairing','1 Find devices; 2 Show my QR and code; 3 Enter pairing link; 4 Pair by address; 5 Remove revokes a saved identity.')+fig('linux','pair-show-qr','Linux pair-only QR','1 Pair QR; 2 Six-digit code; 3 Copy link; 4 New code; 5 Cancel. The pictured code was time-limited and is for illustration only.')+fig('windows','pair-enter-code','Desktop code entry','1 Selected device; 2 Six-digit pairing code; 3 Pair or Cancel.')+fig('android','nearby-verify','Android Nearby verification','Compare the four displayed digits on both phones. Tap Numbers match only if they agree. This is separate from desktop six-digit pairing.')))
    b.append(section(4,labels[3],('<p>ලබන්නාගේ offer එකේ Accept file / Accept transfer ඔබන තුරු transfer ආරම්භ නොවේ. Paired phone එකක Glide open තිබේ නම් desktop එකේ Paired device → Connect භාවිතා කරන්න; QR නැවත scan කිරීමට අවශ්‍ය නැත. QR මඟින් එකවර share කිරීමට Quick share with QR තෝරා phone එකේ Receive files → Scan PC QR code භාවිතා කරන්න. Pair devices QR එක files යැවීමට නොවේ. Progress අවසානයේ verification සහ saving ඉවර වන තුරු රැඳී සිටින්න.</p>' if si else '<p>The receiver must tap Accept file or Accept transfer on every offer, including offers from paired devices. For a saved phone with Glide open, choose Paired device → Connect on the desktop; do not scan QR again. For a one-time QR transfer, choose Quick share with QR on the desktop and Receive files → Scan PC QR code on the phone. The Pair devices QR saves trust but does not send files. Wait for verification and saving before treating the transfer as complete.</p>')+fig('android','send-select-empty','Android file selection','1 Choose photos & videos; 2 Choose files & documents; 3 Choose folder.')+fig('android','send-progress','Android sending','1 Current file and overall progress; 2 Bytes and speed; 3 Cancel transfer.')+fig('android','receive-offer','Android incoming offer','1 Check the sender and file count; 2 Accept file; 3 Decline.')+fig('android','receive-complete','Android verified result','1 Verified item; 2 Share file; 3 Open file; 4 Done.')+''.join(route_card(a,c,si) for a,c in ROUTES)))
    b.append(section(5,labels[4],('<p>Android හි verified file එක ස්වයංක්‍රීයව Download/Glide වෙත save වේ. Settings → Received file save location → Choose folder මඟින් වෙනත් folder එකක් තෝරන්න. Windows/Linux හි destination එක Settings තුළ බලන්න හෝ Change folder / Browse භාවිතා කරන්න. Activity හි Open folder ද තිබේ.</p>' if si else '<p>Android automatically saves verified files to Download/Glide. Change this in Settings → Received file save location → Choose folder. On Windows/Linux, view or change the destination in Settings with Change folder or Browse. Activity also offers Open folder for received items.</p>')+fig('android','settings-storage','Android save location','1 Download/Glide is the default; 2 Choose folder changes it; 3 verified files save automatically.')+fig('linux','settings-upper','Linux Settings','1 Theme; 2 Device Name; 3 Download Directory and Browse; 4 Trusted Devices.')))
    b.append(section(6,labels[5],('<p>Desktop Activity යනු active transfer, result සහ verified history බලන තැනයි. Search history, All/Sent/Received, Open folder, Clear history භාවිතා කරන්න. Failure එකක Technical details විවෘත කර Try again තිබේ නම් නැවත උත්සාහ කරන්න. Android History හි All/Sent/Received filters සහ Clear history තිබේ.</p>' if si else '<p>Desktop Activity shows an active transfer, its result, and verified history. Use Search history, All/Sent/Received filters, Open folder, and Clear history. On failure, expand Technical details and use Try again when offered. Android History has All/Sent/Received filters and Clear history.</p>')+fig('windows','activity-history','Windows Activity','1 Current transfer/result; 2 Search history; 3 All/Sent/Received; 4 Open folder; 5 Clear history.')+fig('linux','activity-sending','Linux Activity','1 Current transfer; 2 progress and speed; 3 Cancel transfer; 4 verified history below.')+fig('android','history','Android Transfer history','1 All/Sent/Received; 2 verified entries; 3 Clear history.')))
    b.append(section(7,labels[6],('<p><b>Windows:</b> Appearance, receive folder, Advanced connection details සහ Trusted devices. <b>Linux:</b> Follow System/Light/Dark, Device Name, Download Directory, Trusted Devices, Listening Port, Export diagnostic logs සහ About. <b>Android:</b> Device name, Theme mode, Dynamic color, Reduced motion, Notification details, Keep screen awake, Manage trusted devices, Received file save location, Nearby diagnostics, About, Privacy policy, Open source licenses. Receive files පිටුවේ Keep receiving in background on කළ විට notification එකක් පෙනේ; Stop මඟින් නව offers නවත්වන්න. Default ලෙස Glide close/background කළ විට idle receiver නවතී. Active transfer එක අවසන් වන තුරු පවතී.</p>' if si else '<p><b>Windows:</b> Appearance, received-file folder, Advanced connection details, and Trusted devices. <b>Linux:</b> Follow System/Light/Dark, Device Name, Download Directory, Trusted Devices, Listening Port, Export diagnostic logs, and About. <b>Android:</b> device name, Theme mode, Dynamic color, Reduced motion, Notification details, Keep screen awake, Manage trusted devices, Received file save location, Nearby diagnostics, About, Privacy policy, and Open source licenses. On Receive files, turn on Keep receiving in background to stay available with a notification; use its Stop action to end idle receiving. By default idle receiving stops when Glide leaves the foreground. An active transfer continues until it finishes.</p>')+fig('android','settings-appearance','Android Appearance','1 Edit device name; 2 Theme mode; 3 Dynamic color; 4 Reduced motion.')+fig('android','settings-trust','Android privacy and trust','1 Notification details; 2 Keep screen awake; 3 Manage trusted devices; 4 Pair with another device.')+fig('linux','settings-lower','Linux advanced settings','1 Trusted Devices and revoke; 2 Listening Port; 3 Export diagnostic logs; 4 About.')))
    recovery=[('No nearby device / device offline','Turn on receiving or Show my QR and code on the other device; check both are on the same LAN; select Try again or Pair by address.'),('Bluetooth or Wi-Fi off','On Android, use Open Bluetooth settings or Open Wi-Fi settings, return to Glide, then Check again.'),('Expired code or link','Use New code / Refresh code on the displaying device and enter or scan the new invitation.'),('Declined or cancelled','Start a new send and obtain receiver approval again. A cancelled attempt is not a verified transfer.'),('Interrupted or failed transfer','Check Activity/History and Technical details, check network and free space, then Try again where offered.'),('Saved file not found','Check Android Download/Glide or the folder in Settings; on desktop use Settings or Activity → Open folder.')]
    cards=''.join(f'<dt>{html.escape(k)}</dt><dd>{html.escape(v)}</dd>' for k,v in recovery)
    b.append(section(8,labels[7],f'<dl>{cards}</dl>'))
    toc=''.join(f'<a href="#s{i}">{i}. {html.escape(n)}</a>' for i,n in enumerate(labels,1))
    css='''*{box-sizing:border-box}body{margin:0;background:#0c1426;color:#f4f7ff;font:16px/1.58 Arial,"Nirmala UI","Noto Sans Sinhala",sans-serif}header{background:#142440;padding:2rem max(20px,calc((100vw - 1050px)/2));border-bottom:3px solid #5f86ed}h1{margin:.2rem 0}nav{display:flex;flex-wrap:wrap;gap:.5rem;padding:1rem max(20px,calc((100vw - 1050px)/2));background:#192b48}a{color:#a9c3ff}nav a{padding:.25rem .55rem}main{max-width:1050px;margin:auto;padding:0 20px 4rem}section{border-bottom:1px solid #324360;padding:1rem 0 2rem}h2{color:#c8d8ff}figure{display:inline-block;vertical-align:top;width:min(100%,490px);margin:12px 12px 12px 0;padding:12px;background:#192841;border:1px solid #354c70;border-radius:12px}figure img{display:block;width:100%;height:auto;max-height:600px;object-fit:contain;background:#101b31}figcaption{font-size:.9rem;margin-top:8px}.route{border:1px solid #344e78;border-radius:12px;padding:12px 18px;margin:12px 0;background:#15243e}li{margin-bottom:.5rem}dt{font-weight:bold;margin-top:1rem}dd{margin-left:0}footer{padding:2rem;text-align:center}@media print{body{background:white;color:#121a2b;font-size:11pt}header,nav{background:white;color:#121a2b;padding:0}nav{display:none}main{max-width:none;padding:0}h2{color:#17366b;break-after:avoid}section{border:0}figure{background:white;border:1px solid #aaa;break-inside:avoid;width:82%;padding:6px}figure img{max-height:12cm}.route{background:white;border:1px solid #999;break-inside:avoid}a{color:#17366b}}'''
    lang='si' if si else 'en'
    return f'<!doctype html><html lang="{lang}"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>{title}</title><style>{css}</style><header><h1>{title}</h1><p>{intro}</p><p><a href="guide-en.html">English</a> · <a href="guide-si.html">සිංහල + English</a></p></header><nav aria-label="Contents">{toc}</nav><main>{"".join(b)}</main><footer>Glide 1.0.0 · 2026-11-12 · Offline guide</footer></html>'

if __name__=='__main__':
    prepare_assets()
    for lang in ('en','si'):(ROOT/f'guide-{lang}.html').write_text(make_html(lang=='si'),encoding='utf-8')
    print('Built screenshot manifest and two offline HTML editions')
