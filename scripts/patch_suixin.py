from __future__ import annotations

import re
import struct
import sys
from pathlib import Path


OLD_PACKAGE = "ml.sky233.zero.music"
NEW_PACKAGE = "com.poyi.suixinyiting"


def replace_text(path: Path, pairs: list[tuple[str, str]]) -> int:
    text = path.read_text(encoding="utf-8")
    original = text
    for old, new in pairs:
        text = text.replace(old, new)
    if text != original:
        path.write_text(text, encoding="utf-8", newline="\n")
        return 1
    return 0


def patch_brand(root: Path) -> int:
    changed = 0
    manifest = root / "AndroidManifest.xml"
    changed += replace_text(
        manifest,
        [
            (OLD_PACKAGE, NEW_PACKAGE),
            ("com.github.sky130.zero.music.main", "com.poyi.suixinyiting.main"),
            ('android:icon="@mipmap/ic_launcher"', 'android:icon="@drawable/ic_suixin_launcher"'),
            ('android:roundIcon="@mipmap/ic_launcher_round"', 'android:roundIcon="@drawable/ic_suixin_launcher"'),
        ],
    )
    text = manifest.read_text(encoding="utf-8")
    # The rebranded build is standalone and must not depend on the mother package.
    text = re.sub(
        r'\s*<queries><package android:name="(?:ml\.sky233\.zero\.music|com\.poyi\.suixinyiting)" /></queries>',
        "",
        text,
    )
    manifest.write_text(text, encoding="utf-8", newline="\n")
    changed += 1

    network_security = root / "res" / "xml" / "network_security_config.xml"
    if network_security.exists():
        security_text = network_security.read_text(encoding="utf-8")
        if "<domain includeSubdomains=\"true\">music.126.net</domain>" not in security_text:
            security_text = security_text.replace(
                "</network-security-config>",
                """    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="true">music.126.net</domain>
    </domain-config>
</network-security-config>""",
            )
            network_security.write_text(security_text, encoding="utf-8", newline="\n")
            changed += 1

    visible_pairs = [
        ("零度音乐 Pro", "随心一听完整功能"),
        ("零度音乐", "随心一听"),
        ("零音助手", "随心助手"),
        ("购买 Pro", "完整功能已开放"),
        ("开通 Pro", "使用完整功能"),
        ("管理 Pro", "完整功能"),
        ("Pro 已开通", "完整功能已开放"),
        ("Pro 功能已激活", "全部功能已开放"),
        ("Pro 功能", "完整功能"),
        ("恢复购买", "功能状态"),
        ("购买记录", "功能说明"),
        ("微信支付", "开放版本"),
        ("请使用微信扫码支付", "全部功能已开放"),
        ("支付成功", "功能已开放"),
        ("支付失败", "功能状态"),
        ("Sky233", "随心一听社区"),
        ("sky233@lightxi.com", "community@suixinyiting.local"),
        ("珠海市晞云云科技有限公司", "随心一听开源社区"),
        ("晞云公司", "开源社区"),
        ("Developed by Sky233", "随心一听开源版"),
        ("android:text=\"Pro\"", "android:text=\"完整\""),
        (OLD_PACKAGE, NEW_PACKAGE),
    ]
    candidates = [root / "res" / "values" / "strings.xml"]
    candidates += list((root / "assets").rglob("*.txt"))
    candidates += list((root / "assets").rglob("*.md"))
    candidates += list((root / "assets").rglob("*.yaml"))
    candidates += list((root / "assets").rglob("*.json"))
    candidates += list((root / "res").glob("layout*/*.xml"))
    for path in candidates:
        changed += replace_text(path, visible_pairs)

    strings = root / "res" / "values" / "strings.xml"
    text = strings.read_text(encoding="utf-8")
    text = re.sub(r'(<string name="app_name">).*?(</string>)', r'\1随心一听\2', text)
    text = re.sub(r'(<string name="zero_music_pro">).*?(</string>)', r'\1随心一听完整功能\2', text)
    open_copy = {
        "move_pro_card_to_bottom": "完整功能卡片移至底部",
        "pro_desc": "当前版本已开放全部高级功能，无需激活或购买。",
        "pro_lifetime": "完整功能永久开放",
        "pro_manage_short": "已开放",
        "pro_support_reminder_action": "知道了",
        "pro_support_reminder_content": "当前版本已开放全部现有功能，可直接使用。",
        "pro_support_reminder_later": "关闭",
        "pro_unlock_all": "全部高级功能已开放",
        "pro_upgrade_action_with_arrow": "已开放",
        "pro_upgrade_subtitle": "所有高级功能均可直接使用",
        "pro_upgrade_title": "完整功能",
    }
    for name, value in open_copy.items():
        text = re.sub(
            rf'(<string name="{re.escape(name)}">).*?(</string>)',
            rf"\g<1>{value}\g<2>",
            text,
            flags=re.S,
        )
    strings.write_text(text, encoding="utf-8", newline="\n")
    return changed


def patch_version(root: Path) -> None:
    yml = root / "apktool.yml"
    text = yml.read_text(encoding="utf-8")
    text = re.sub(r"(?m)^  versionCode: .*?$", "  versionCode: 10400", text)
    text = re.sub(r"(?m)^  versionName: .*?$", "  versionName: 1.4.0", text)
    yml.write_text(text, encoding="utf-8", newline="\n")


def patch_unlock(root: Path) -> list[str]:
    touched: list[str] = []

    global_flow = root / "smali" / "ˁ" / "Ԩ.smali"
    text = global_flow.read_text(encoding="utf-8")
    old = "sget-object v0, Ljava/lang/Boolean;->FALSE:Ljava/lang/Boolean;"
    new = "sget-object v0, Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;"
    if old in text:
        global_flow.write_text(text.replace(old, new, 1), encoding="utf-8", newline="\n")
    elif new not in text:
        raise RuntimeError("global Pro StateFlow initializer not found")
    touched.append(str(global_flow))

    license_pref = root / "smali" / "ˤ" / "ނ.smali"
    text = license_pref.read_text(encoding="utf-8")
    marker = 'const-string p1, "license_isPro"'
    pos = text.index(marker)
    call = "invoke-virtual {p0, p1, v1}, Lkndroidx/setting/kn/NativeSetting;->boolean"
    call_pos = text.index(call, pos)
    before_call = text[max(pos, call_pos - 80):call_pos]
    if "const/4 v1, 0x1" not in before_call:
        text = text[:call_pos] + "const/4 v1, 0x1\n\n    " + text[call_pos:]
    license_pref.write_text(text, encoding="utf-8", newline="\n")
    touched.append(str(license_pref))

    listener_files = []
    for path in root.rglob("*.smali"):
        body = path.read_text(encoding="utf-8")
        if ".implements Lzero/music/ProStatusListener;" in body and "KneeProStatusListener" not in str(path):
            listener_files.append(path)
    if len(listener_files) != 1:
        raise RuntimeError(f"expected one app ProStatusListener, got {listener_files}")
    listener = listener_files[0]
    text = listener.read_text(encoding="utf-8")
    start = text.index(".method public onChanged(Z)V")
    end = text.index(".end method", start) + len(".end method")
    method = """.method public onChanged(Z)V
    .locals 2

    sget-object v0, Lˁ/Ԩ;->Ϳ:Lkotlinx/coroutines/flow/MutableStateFlow;
    sget-object v1, Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;
    invoke-interface {v0, v1}, Lkotlinx/coroutines/flow/MutableStateFlow;->setValue(Ljava/lang/Object;)V

    iget-object v0, p0, Lࢨ/ށ;->ؠ:Ljava/lang/Object;
    check-cast v0, Lcom/github/sky130/zero/music/MainApplication;
    invoke-virtual {v0}, Lcom/github/sky130/zero/music/MainApplication;->Ϳ()V
    return-void
.end method"""
    listener.write_text(text[:start] + method + text[end:], encoding="utf-8", newline="\n")
    touched.append(str(listener))

    # Splash waits on this application-ready flow; service initialization continues in parallel.
    app = root / "smali" / "com" / "github" / "sky130" / "zero" / "music" / "MainApplication.smali"
    text = app.read_text(encoding="utf-8")
    field_pos = text.index(".field public static final ރ:Lkotlinx/coroutines/flow/MutableStateFlow;")
    false_line = "sget-object v0, Ljava/lang/Boolean;->FALSE:Ljava/lang/Boolean;"
    true_line = "sget-object v0, Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;"
    tail = text[field_pos:]
    if false_line in tail:
        false_pos = text.index(false_line, field_pos)
        text = text[:false_pos] + text[false_pos:].replace(false_line, true_line, 1)
    elif true_line not in tail:
        raise RuntimeError("application-ready StateFlow initializer not found")
    app.write_text(text, encoding="utf-8", newline="\n")
    touched.append(str(app))
    return touched


def patch_sticky_feature_rights(root: Path) -> list[str]:
    """Keep every Java-side feature-rights mirror true, including stale preferences."""
    touched: list[str] = []
    smali_root = root / "smali"

    candidates: list[tuple[Path, str]] = []
    for path in smali_root.rglob("*.smali"):
        body = path.read_text(encoding="utf-8")
        if 'const-string p1, "license_isPro"' in body:
            candidates.append((path, body))
    if len(candidates) != 1:
        raise RuntimeError(f"expected one license settings class, got {[p for p, _ in candidates]}")

    settings_path, text = candidates[0]
    marker = 'const-string p1, "license_isPro"'
    pos = text.index(marker)
    iput_pos = text.index("    iput-object p1, p0, ", pos)
    iput_end = text.index("\n", iput_pos) + 1
    sticky_marker = "Keep the compatibility edition feature flag sticky"
    sticky = """
    # Keep the compatibility edition feature flag sticky even when an old
    # preference or a later license callback previously stored false.
    sget-object v2, Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;

    invoke-virtual {p1, v2}, Lkndroidx/setting/SettingField;->setValue(Ljava/lang/Object;)V
"""
    if sticky_marker not in text:
        text = text[:iput_end] + sticky + text[iput_end:]
        settings_path.write_text(text, encoding="utf-8", newline="\n")
    touched.append(str(settings_path))

    class_descriptor = next(
        line.split()[-1][:-1] for line in text.splitlines() if line.startswith(".class")
    )
    tail = text[pos:pos + 1000]
    field_name = tail.split("iput-object p1, p0, ", 1)[1].split(":", 1)[0].split("->", 1)[1]
    field_ref = f"{class_descriptor};->{field_name}:Lkndroidx/setting/SettingField;"

    writer_count = 0
    for path in smali_root.rglob("*.smali"):
        body = path.read_text(encoding="utf-8")
        if field_ref not in body or "GetLicenseDetailReply" not in body:
            continue
        lines = body.splitlines(True)
        for i, line in enumerate(lines):
            if field_ref not in line:
                continue
            window = "".join(lines[i:i + 18])
            if "GetLicenseDetailReply" not in window or "valueOf(Z)" not in window:
                continue
            start = end = None
            for j in range(i + 1, min(i + 15, len(lines))):
                if "iget-boolean " in lines[j] and "GetLicenseDetailReply" in lines[j]:
                    start = j
                if start is not None and "move-result-object " in lines[j]:
                    end = j
                    break
            if start is not None and end is not None:
                register = lines[end].strip().split()[-1]
                lines[start:end + 1] = [
                    f"    sget-object {register}, Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;\n"
                ]
                path.write_text("".join(lines), encoding="utf-8", newline="\n")
                touched.append(str(path))
                writer_count += 1
                break
    # An already-patched tree has no remaining Boolean.valueOf network writer.
    if writer_count == 0:
        already_forced = any(
            field_ref in p.read_text(encoding="utf-8")
            and "GetLicenseDetailReply" in p.read_text(encoding="utf-8")
            and "Ljava/lang/Boolean;->TRUE" in p.read_text(encoding="utf-8")
            for p in smali_root.rglob("*.smali")
        )
        if not already_forced:
            raise RuntimeError("license detail feature-rights writer not found")

    # License-detail UI has a separate direct read of the reply's isPro bit.
    # Force external consumers true while leaving model serialization/equality intact.
    direct_read = re.compile(
        r"(?m)^(\s*)iget-boolean\s+([vp]\d+),\s+[vp]\d+,\s+"
        r"Lcom/github/sky130/zero/network/model/GetLicenseDetailReply;->[^:]+:Z$"
    )
    for path in smali_root.rglob("*.smali"):
        relative = path.relative_to(smali_root).as_posix()
        if relative.startswith("com/github/sky130/zero/network/model/"):
            continue
        body = path.read_text(encoding="utf-8")
        patched, count = direct_read.subn(r"\1const/4 \2, 0x1", body)
        if count:
            path.write_text(patched, encoding="utf-8", newline="\n")
            if str(path) not in touched:
                touched.append(str(path))
    return touched


def patch_native_security(root: Path) -> str:
    """Register native methods with this app and tolerate the mother identity check."""
    app = root / "smali" / "com" / "github" / "sky130" / "zero" / "music" / "MainApplication.smali"
    text = app.read_text(encoding="utf-8")
    desired = """:try_start_suixin_native
    invoke-static {p0}, Lzero/music/NativeSecurityBridge;->initialize(Landroid/content/Context;)V
    :try_end_suixin_native
    goto :suixin_native_done
    .catch Ljava/lang/SecurityException; {:try_start_suixin_native .. :try_end_suixin_native} :catch_suixin_native

    :catch_suixin_native
    move-exception v0

    :suixin_native_done"""
    if desired not in text:
        variants = [
            "    invoke-static {p0}, Lzero/music/NativeSecurityBridge;->initialize(Landroid/content/Context;)V",
            """    const-string v0, "ml.sky233.zero.music"
    const/4 v1, 0x2
    invoke-virtual {p0, v0, v1}, Landroid/content/Context;->createPackageContext(Ljava/lang/String;I)Landroid/content/Context;
    move-result-object v0
    invoke-static {v0}, Lzero/music/NativeSecurityBridge;->initialize(Landroid/content/Context;)V""",
            """    new-instance v0, Lcom/poyi/suixinyiting/SecurityContext;
    invoke-direct {v0, p0}, Lcom/poyi/suixinyiting/SecurityContext;-><init>(Landroid/content/Context;)V
    invoke-static {v0}, Lzero/music/NativeSecurityBridge;->initialize(Landroid/content/Context;)V""",
            "    # Rebranded build does not initialize the mother package security context.\n    nop",
        ]
        for old in variants:
            if old in text:
                text = text.replace(old, desired, 1)
                app.write_text(text, encoding="utf-8", newline="\n")
                break
        else:
            raise RuntimeError("NativeSecurityBridge.initialize call not found")
    return str(app)


def patch_native_license_bridges(root: Path) -> list[str]:
    """Replace startup-critical JNI license shims with local equivalents."""
    touched: list[str] = []

    manager = root / "smali" / "zero" / "music" / "LicenseManager.smali"
    text = manager.read_text(encoding="utf-8")
    start = text.index(".method public final setProStatusListener(Lzero/music/ProStatusListener;)V")
    end = text.index(".end method", start) + len(".end method")
    method = """.method public final setProStatusListener(Lzero/music/ProStatusListener;)V
    .locals 1

    const/4 v0, 0x1
    invoke-interface {p1, v0}, Lzero/music/ProStatusListener;->onChanged(Z)V
    return-void
.end method"""
    if text[start:end] != method:
        manager.write_text(text[:start] + method + text[end:], encoding="utf-8", newline="\n")
    touched.append(str(manager))

    text = manager.read_text(encoding="utf-8")
    start = text.index(".method public final initialize(Lߣ/Ԫ;)Ljava/lang/Object;")
    end = text.index(".end method", start) + len(".end method")
    method = """.method public final initialize(Lߣ/Ԫ;)Ljava/lang/Object;
    .locals 1

    # License persistence is local in the open build; initialization succeeds immediately.
    sget-object v0, Lไ/ތ;->Ϳ:Lไ/ތ;
    return-object v0
.end method"""
    if text[start:end] != method:
        manager.write_text(text[:start] + method + text[end:], encoding="utf-8", newline="\n")
    return touched


def patch_arm_native_security(root: Path) -> str:
    """Keep Knee/JNI registration and branch around the later ARMv7 identity check."""
    so = root / "lib" / "armeabi-v7a" / "libzero_native.so"
    data = bytearray(so.read_bytes())
    branch_at = 0x429D98
    target = 0x42B8D8
    original = bytes.fromhex("c4169de5")  # ldr r1, [sp, #0x6c4]
    delta = target - (branch_at + 8)
    patched = struct.pack("<I", 0xEA000000 | ((delta >> 2) & 0x00FFFFFF))
    current = bytes(data[branch_at:branch_at + 4])
    if current == original:
        data[branch_at:branch_at + 4] = patched
        so.write_bytes(data)
    elif current != patched:
        raise RuntimeError(
            f"unexpected ARM security branch bytes at {branch_at:#x}: {current.hex()}"
        )
    return str(so)


def patch_network_bridge(root: Path) -> list[str]:
    touched: list[str] = []
    manifest = root / "AndroidManifest.xml"
    text = manifest.read_text(encoding="utf-8")
    activity = (
        '        <activity android:exported="false" '
        'android:name="com.poyi.suixinyiting.network.NetworkMusicActivity" />'
    )
    service = (
        '        <service android:exported="false" android:foregroundServiceType="mediaPlayback" '
        'android:name="com.poyi.suixinyiting.network.NetworkStreamService" />'
    )
    receiver = (
        '        <receiver android:exported="false" '
        'android:name="com.poyi.suixinyiting.network.NetworkMediaButtonReceiver">'
        '<intent-filter><action android:name="android.intent.action.MEDIA_BUTTON" />'
        '</intent-filter></receiver>'
    )
    additions = [item for item in (activity, service, receiver) if item not in text]
    if additions:
        text = text.replace("</application>", "\n".join(additions) + "\n    </application>", 1)
        manifest.write_text(text, encoding="utf-8", newline="\n")
    touched.append(str(manifest))

    menu = root / "smali" / "com" / "github" / "sky130" / "zero" / "music" / "ui" / "menu" / "MenuActivity.smali"
    text = menu.read_text(encoding="utf-8")
    start = text.index(".method public final init()V")
    end = text.index(".end method", start)
    method = text[start:end]
    hook = (
        "    invoke-static {p0}, "
        "Lcom/poyi/suixinyiting/network/NetworkEntry;->install(Landroid/app/Activity;)V\n\n"
    )
    if hook in method:
        text = text.replace(hook, "", 1)
        menu.write_text(text, encoding="utf-8", newline="\n")
    touched.append(str(menu))

    router = root / "smali" / "ٵ" / "Ϳ.smali"
    text = router.read_text(encoding="utf-8")
    marker = "->open(Landroid/app/Activity;I)Z"
    if marker not in text:
        text = text.replace(
            ".method public final invoke()Ljava/lang/Object;\n    .locals 5",
            ".method public final invoke()Ljava/lang/Object;\n    .locals 6",
            1,
        )
        anchor = (
            "    iget-object v4, p0, Lٵ/Ϳ;->ؠ:"
            "Lcom/github/sky130/zero/music/ui/menu/MenuActivity;\n"
        )
        route = """

    invoke-static {v4, v0}, Lcom/poyi/suixinyiting/network/NetworkMenuRouter;->open(Landroid/app/Activity;I)Z

    move-result v5

    if-eqz v5, :suixin_original_menu_route

    return-object v3

    :suixin_original_menu_route
"""
        if anchor not in text:
            raise RuntimeError("menu click router anchor not found")
        text = text.replace(anchor, anchor + route, 1)
        router.write_text(text, encoding="utf-8", newline="\n")
    touched.append(str(router))
    return touched


def patch_keep_screen_on(root: Path) -> str:
    main = (
        root
        / "smali"
        / "com"
        / "github"
        / "sky130"
        / "zero"
        / "music"
        / "ui"
        / "main"
        / "MainActivity.smali"
    )
    text = main.read_text(encoding="utf-8")
    start = text.index(".method public final onResume()V")
    end = text.index(".end method", start)
    method = text[start:end]
    marker = "Landroid/view/Window;->addFlags(I)V"
    if marker not in method:
        anchor = (
            "    invoke-super {p0}, "
            "Landroidx/fragment/app/ޟ;->onResume()V\n"
        )
        keep_awake = """

    # The watch firmware may force sleep independently of screen_off_timeout.
    # Keep the foreground player window bright and let it wake the display.
    invoke-virtual {p0}, Landroid/app/Activity;->getWindow()Landroid/view/Window;

    move-result-object v5

    const/16 v4, 0x80

    invoke-virtual {v5, v4}, Landroid/view/Window;->addFlags(I)V

    const/4 v4, 0x1

    invoke-virtual {p0, v4}, Landroid/app/Activity;->setTurnScreenOn(Z)V
"""
        if anchor not in method:
            raise RuntimeError("MainActivity.onResume anchor not found")
        text = text[:start] + method.replace(anchor, anchor + keep_awake, 1) + text[end:]
        main.write_text(text, encoding="utf-8", newline="\n")
    text = main.read_text(encoding="utf-8")
    start = text.index(".method public final onResume()V")
    end = text.index(".end method", start)
    method = text[start:end]
    bridge_hook = (
        "    invoke-static {p0}, "
        "Lcom/poyi/suixinyiting/network/NetworkPlaybackBridge;"
        "->install(Landroid/app/Activity;)V\n"
    )
    if bridge_hook not in method:
        anchor = (
            "    invoke-super {p0}, "
            "Landroidx/fragment/app/ޟ;->onResume()V\n"
        )
        if anchor not in method:
            raise RuntimeError("MainActivity.onResume network bridge anchor not found")
        text = text[:start] + method.replace(anchor, anchor + "\n" + bridge_hook, 1) + text[end:]
        main.write_text(text, encoding="utf-8", newline="\n")
    text = main.read_text(encoding="utf-8")
    rotary_start = text.index(
        ".method public final onGenericMotionEvent(Landroid/view/MotionEvent;)Z"
    )
    rotary_end = text.index(".end method", rotary_start)
    rotary_method = text[rotary_start:rotary_end]
    rotary_marker = "->onRotary(Landroid/app/Activity;Landroid/view/MotionEvent;)Z"
    if rotary_marker not in rotary_method:
        locals_anchor = "    .locals 9\n"
        rotary_hook = """

    invoke-static {p0, p1}, Lcom/poyi/suixinyiting/network/NetworkPlaybackBridge;->onRotary(Landroid/app/Activity;Landroid/view/MotionEvent;)Z

    move-result v0

    if-eqz v0, :suixin_original_rotary

    const/4 v0, 0x1

    return v0

    :suixin_original_rotary
"""
        if locals_anchor not in rotary_method:
            raise RuntimeError("MainActivity.onGenericMotionEvent locals anchor not found")
        text = (
            text[:rotary_start]
            + rotary_method.replace(locals_anchor, locals_anchor + rotary_hook, 1)
            + text[rotary_end:]
        )
        main.write_text(text, encoding="utf-8", newline="\n")
    return str(main)


def write_icon(root: Path) -> None:
    icon = root / "res" / "drawable" / "ic_suixin_launcher.xml"
    icon.write_text(
        """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#5B82FF" android:pathData="M54,4A50,50 0,1 0,54 104A50,50 0,1 0,54 4" />
    <path android:fillColor="#FFFFFF" android:pathData="M67,24L67,63C64,61 60,60 56,61C49,62 44,67 44,73C44,80 50,85 58,84C66,83 71,77 71,69L71,39L88,35L88,55C85,53 81,52 77,53C70,54 65,59 65,65C65,72 71,77 79,76C87,75 92,69 92,61L92,20Z" />
    <path android:fillColor="#FFFFFF" android:pathData="M31,36C24,36 18,42 18,50C18,59 25,66 35,74L44,81L51,75C42,70 36,65 32,60C27,54 26,48 31,44C35,41 39,42 43,46L48,51L53,46C55,44 57,43 59,43L59,34C55,34 51,36 48,39C43,37 38,36 31,36Z" />
</vector>
""",
        encoding="utf-8",
        newline="\n",
    )


def main() -> None:
    root = Path(sys.argv[1] if len(sys.argv) > 1 else "work/suixin-apktool").resolve()
    if not (root / "apktool.yml").exists():
        raise SystemExit(f"apktool project missing: {root}")
    changed = patch_brand(root)
    patch_version(root)
    write_icon(root)
    unlock = patch_unlock(root)
    sticky_rights = patch_sticky_feature_rights(root)
    native_security = patch_native_security(root)
    native_bridges = patch_native_license_bridges(root)
    network_bridge = patch_network_bridge(root)
    keep_screen_on = patch_keep_screen_on(root)
    print(f"brand files changed: {changed}")
    print("unlock files:")
    for path in unlock:
        print(path)
    print("sticky feature-rights files:")
    for path in sticky_rights:
        print(path)
    print(f"native security patch: {native_security}")
    print("native license bridges:")
    for path in native_bridges:
        print(path)
    print("network bridge hooks:")
    for path in network_bridge:
        print(path)
    print(f"keep-screen-on patch: {keep_screen_on}")


if __name__ == "__main__":
    main()
