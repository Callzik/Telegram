"""HTML / Markdown text to Telegram message + entities (exteraGram's text_formatting)."""

from html.parser import HTMLParser

import markdown_utils
from markdown_utils import RawEntity, _u16

_TAGS = {
    "b": "bold", "strong": "bold", "i": "italic", "em": "italic", "u": "underline", "ins": "underline",
    "s": "strikethrough", "strike": "strikethrough", "del": "strikethrough", "code": "code",
    "pre": "pre", "a": "text_link", "blockquote": "blockquote", "tg-spoiler": "spoiler", "spoiler": "spoiler",
}


class _Parser(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.parts, self.length, self.stack, self.entities = [], 0, [], []

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag == "br":
            self.handle_data("\n")
            return
        if tag == "span" and a.get("class") == "tg-spoiler":
            tag = "tg-spoiler"
        kind = _TAGS.get(tag)
        if kind:
            self.stack.append((tag, kind, self.length, a))

    def handle_endtag(self, tag):
        for i in range(len(self.stack) - 1, -1, -1):
            t, kind, start, a = self.stack[i]
            if t == tag or (tag == "span" and t == "tg-spoiler"):
                del self.stack[i]
                if self.length > start:
                    self.entities.append(RawEntity(kind, start, self.length - start,
                                                   url=a.get("href"), language=a.get("language")))
                return

    def handle_data(self, data):
        self.parts.append(data)
        self.length += _u16(data)


def parse_text(text, parse_mode="HTML"):
    """Returns {"message": str, "entities": [TLRPC.MessageEntity]}."""
    if str(parse_mode).lower() in ("markdown", "md"):
        parsed = markdown_utils.parse_markdown(text)
        message, entities = parsed.text, parsed.entities
    else:
        p = _Parser()
        p.feed(str(text))
        p.close()
        message, entities = "".join(p.parts), sorted(p.entities, key=lambda e: e.offset)
    return {"message": message, "entities": [e.to_tlrpc_object() for e in entities]}
