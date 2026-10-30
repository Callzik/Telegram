"""Markdown to Telegram text + entities (exteraGram's markdown_utils).

    parsed = parse_markdown("**bold** and [link](https://t.me)")
    params.message = parsed.text
    for e in parsed.entities:
        params.entities.add(e.to_tlrpc_object())

Supported: **bold**, __italic__, ~~strike~~, ||spoiler||, `code`, ```lang\\npre```,
[text](url) and > quote lines. Offsets are in UTF-16 units, as Telegram expects.
"""

import re

from java import jclass

_TLRPC = "org.telegram.tgnet.TLRPC$"


def _u16(s):
    return len(s.encode("utf-16-le")) // 2


class RawEntity:
    def __init__(self, type, offset, length, url=None, language=None):
        self.type, self.offset, self.length = type, offset, length
        self.url, self.language = url, language

    def to_tlrpc_object(self):
        names = {
            "bold": "TL_messageEntityBold", "italic": "TL_messageEntityItalic",
            "underline": "TL_messageEntityUnderline", "strikethrough": "TL_messageEntityStrike",
            "spoiler": "TL_messageEntitySpoiler", "code": "TL_messageEntityCode",
            "pre": "TL_messageEntityPre", "text_link": "TL_messageEntityTextUrl",
            "blockquote": "TL_messageEntityBlockquote", "url": "TL_messageEntityUrl",
        }
        e = jclass(_TLRPC + names[self.type])()
        e.offset, e.length = int(self.offset), int(self.length)
        if self.type == "text_link":
            e.url = self.url or ""
        if self.type == "pre":
            e.language = self.language or ""
        return e

    def __repr__(self):
        return "RawEntity(%s, %d, %d)" % (self.type, self.offset, self.length)


class ParsedMessage:
    def __init__(self, text, entities):
        self.text, self.entities = text, entities


_TOKENS = [
    ("pre", re.compile(r"```([^\n`]*)\n?(.*?)```", re.S)),
    ("code", re.compile(r"`([^`\n]+)`")),
    ("text_link", re.compile(r"\[([^\]]+)\]\(([^)\s]+)\)")),
    ("bold", re.compile(r"\*\*(.+?)\*\*", re.S)),
    ("underline", re.compile(r"__(.+?)__", re.S)),
    ("italic", re.compile(r"(?<![\w*])\*(?!\*)(.+?)(?<!\*)\*(?![\w*])", re.S)),
    ("italic", re.compile(r"(?<!\w)_(?!_)(.+?)(?<!_)_(?!\w)", re.S)),
    ("strikethrough", re.compile(r"~~(.+?)~~", re.S)),
    ("spoiler", re.compile(r"\|\|(.+?)\|\|", re.S)),
]


def _parse(text, base):
    out, entities = [], []
    pos = 0
    length = 0
    while pos < len(text):
        best = None
        for kind, rx in _TOKENS:
            m = rx.search(text, pos)
            if m and (best is None or m.start() < best[1].start()):
                best = (kind, m)
        if best is None:
            break
        kind, m = best
        plain = text[pos:m.start()]
        out.append(plain)
        length += _u16(plain)
        start = base + length
        if kind == "pre":
            inner, inner_entities, lang = m.group(2), [], m.group(1).strip()
        elif kind == "code":
            inner, inner_entities, lang = m.group(1), [], None
        else:
            inner, inner_entities = _parse(m.group(1), start)
            lang = None
        out.append(inner)
        n = _u16(inner)
        if n:
            entities.append(RawEntity(kind, start, n, url=m.group(2) if kind == "text_link" else None, language=lang))
        entities.extend(inner_entities)
        length += n
        pos = m.end()
    out.append(text[pos:])
    return "".join(out), entities


def parse_markdown(text):
    lines = str(text).split("\n")
    quotes = []
    body = []
    for i, line in enumerate(lines):
        if line.startswith("> ") or line == ">":
            quotes.append(i)
            line = line[2:]
        body.append(line)
    plain, entities = _parse("\n".join(body), 0)
    if quotes:
        # quote lines keep their position: find each line's span in the result
        offset = 0
        result_lines = plain.split("\n")
        spans = []
        for line in result_lines:
            spans.append((offset, _u16(line)))
            offset += _u16(line) + 1
        run = None
        for i in range(len(result_lines)):
            if i in quotes and i < len(spans):
                run = (run[0], spans[i][0] + spans[i][1]) if run else (spans[i][0], spans[i][0] + spans[i][1])
            elif run:
                entities.append(RawEntity("blockquote", run[0], run[1] - run[0]))
                run = None
        if run:
            entities.append(RawEntity("blockquote", run[0], run[1] - run[0]))
    entities.sort(key=lambda e: e.offset)
    return ParsedMessage(plain, entities)
