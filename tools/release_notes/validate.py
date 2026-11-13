#!/usr/bin/env python3
"""Fail-closed Russian human-facing FT release notes gate. Run BEFORE GitHub release API."""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

REQUIRED = ("Что нового", "Что исправлено", "Статус")
ALLOWED = ("Что нового", "Что исправлено", "Что проверить на машине", "Статус")
FORBIDDEN = re.compile(
    r"(?i)\b(?:sha(?:[ -]?256)?|commit|workflow|ci|github[ -]?actions|run[ -]?id|job[ -]?id|"
    r"fingerprint|checksum|certificate)\b|\b[0-9a-f]{40,64}\b|"
    r"\b(?:сертификат|хеш|хэш|отпечаток подписи|коммит|ветка сборки)\b"
)
PLACEHOLDER = re.compile(
    r"(?i)\b(?:todo|tbd|xxx|заполнить|вставьте|опишите|укажите)\b|"
    r"<[^>]{3,}>|\.{3,}|\[описание\]"
)
GENERIC = re.compile(
    r"(?iu)^(?:различные (?:исправления|улучшения)|исправлены ошибки|"
    r"улучшена стабильность|повышена производительность|мелкие исправления)\.?$"
)
NO_NEW = re.compile(r"(?iu)^новых функций в этой сборке нет\.?$")
NO_FIX = re.compile(r"(?iu)^исправлений в этой сборке нет\.?$")


def validate(tag: str, body: str, previous: str | None = None) -> list[str]:
    errors: list[str] = []
    match = re.search(r"(?:^|[-_])ft0*(\d+)(?=$|[-_])", tag, re.I)
    if not match:
        return ["Тег должен содержать номер вида ft183."]
    version = str(int(match.group(1)))
    content = body.strip()
    if len(content) < 160 or len(content) > 20000:
        errors.append("Описание должно быть содержательным (160–20000 символов).")
    if not re.search(rf"(?im)^#\s*FT\s*0*{version}\s*[—–:-]\s*[^\s].+", content):
        errors.append(f"Заголовок должен начинаться с «# FT{version} — ...».")
    if not re.search(r"[А-Яа-яЁё]{4,}", content):
        errors.append("Требуется русский текст.")
    if FORBIDDEN.search(content):
        errors.append("Технические хеши, CI, коммиты или сертификаты запрещены в публичном тексте.")
    if PLACEHOLDER.search(content):
        errors.append("Обнаружен шаблонный текст или незаполненное поле.")
    sections: dict[str, str] = {}
    current = None
    for line in content.splitlines():
        heading = re.match(r"^##\s+(.+?)\s*$", line)
        if heading:
            current = heading.group(1).strip()
            if current in sections:
                errors.append(f"Повторяется раздел «{current}».")
            sections[current] = ""
        elif current:
            sections[current] = sections.get(current, "") + line + "\n"
    for heading in REQUIRED:
        if not sections.get(heading, "").strip():
            errors.append(f"Нет непустого раздела «{heading}».")
    for heading in sections:
        if heading not in ALLOWED:
            errors.append(f"Лишний/неизвестный раздел: {heading}.")
    substantial = 0
    for heading, nothing_re in (("Что нового", NO_NEW), ("Что исправлено", NO_FIX)):
        text = sections.get(heading, "")
        bullets = [m.group(1).strip() for m in re.finditer(r"(?m)^\s*[-*]\s+(.+)$", text)]
        if not bullets:
            errors.append(f"Раздел «{heading}» должен содержать список конкретных изменений.")
        for item in bullets:
            if nothing_re.fullmatch(item):
                continue
            if len(item) < 35 or not re.search(r"[А-Яа-яЁё]{4,}", item) or GENERIC.fullmatch(item):
                errors.append(f"Неконкретное изменение в «{heading}»: {item[:65]}")
            else:
                substantial += 1
    if not substantial:
        errors.append("Нет ни одного конкретного нового изменения или исправления.")
    status = sections.get("Статус", "").strip()
    if status and len(status) < 25:
        errors.append("В «Статус» укажите реальный статус проверки на автомобиле.")
    if previous is not None:
        old = re.sub(r"(?m)^#\s*FT\s*\d+\s*[—–:-].*$", "", previous).strip()
        new = re.sub(r"(?m)^#\s*FT\s*\d+\s*[—–:-].*$", "", content).strip()
        if old == new:
            errors.append("Описание полностью совпадает с предыдущим опубликованным релизом.")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", required=True, help="Exact published tag, e.g. xa60-ft183-abcd1234")
    parser.add_argument("--file", required=True, type=Path, help="Russian Markdown release body")
    parser.add_argument("--previous", type=Path, help="Prior release Markdown body, if available")
    args = parser.parse_args()
    try:
        body = args.file.read_text(encoding="utf-8")
        previous = args.previous.read_text(encoding="utf-8") if args.previous else None
    except (OSError, UnicodeError) as exc:
        print(f"BLOCKED: cannot read release notes: {exc}", file=sys.stderr)
        return 1
    errors = validate(args.tag, body, previous)
    if errors:
        for error in errors:
            print("BLOCKED: " + error, file=sys.stderr)
        return 1
    print(f"PASS: русское описание {args.tag} готово к публикации.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
