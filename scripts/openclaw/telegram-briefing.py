#!/usr/bin/env python3
"""Track selected public sources and generate cited changes; never send messages."""

import argparse
from datetime import datetime, timezone
import difflib
from email.utils import parsedate_to_datetime
import fcntl
import hashlib
from html.parser import HTMLParser
import http.client
import importlib.util
import ipaddress
import json
import os
from pathlib import Path
import queue
import re
import socket
import ssl
import sys
import tempfile
import threading
import time
import unicodedata
from urllib.parse import parse_qsl, quote, urljoin, urlsplit, urlunsplit
import uuid
import xml.etree.ElementTree as ET


DEFAULT_STATE = Path.home() / ".openclaw-personaledge"
MAX_BODY_BYTES = 2 * 1024 * 1024
MAX_TEXT_CHARS = 1000000
MAX_TEXT_LINES = 5000
MAX_SOURCES = 12
REQUEST_SECONDS = 25
SECRET_QUERY_KEYS = {"token", "access_token", "api_key", "apikey", "key", "password", "secret",
                     "auth", "authorization", "signature", "x-amz-signature", "x-goog-signature"}
USER_AGENT = "OpenClaw-PublicBriefing/1.0 (public sources; no cookies or authentication)"


class BriefingError(ValueError):
    """An intentionally content-free error code safe in an operations receipt."""


def require(condition, code):
    if not condition:
        raise BriefingError(code)


def now_iso():
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def sha256_text(value):
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def safe_path(path):
    require(path.is_absolute() and ".." not in path.parts, "UNSAFE_STATE_PATH")
    for ancestor in (path, *path.parents):
        require(not ancestor.is_symlink(), "SYMLINKED_STATE_PATH")


def private_dir(path):
    safe_path(path)
    if path.exists():
        info = path.stat()
        require(path.is_dir() and info.st_uid == os.getuid() and info.st_mode & 0o077 == 0,
                "STATE_DIRECTORY_NOT_PRIVATE")
    path.mkdir(parents=True, exist_ok=True, mode=0o700)


def atomic_json(path, value):
    atomic_bytes(path, (json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode())


def atomic_bytes(path, content):
    private_dir(path.parent)
    safe_path(path)
    fd, temporary = tempfile.mkstemp(prefix="." + path.name + "-", dir=str(path.parent))
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def read_json(path, default=None):
    safe_path(path)
    if not path.exists():
        return default
    info = path.stat()
    require(path.is_file() and info.st_uid == os.getuid() and info.st_mode & 0o077 == 0,
            "STATE_FILE_NOT_PRIVATE")
    try:
        return json.loads(path.read_text())
    except (ValueError, UnicodeError):
        raise BriefingError("INVALID_LOCAL_STATE") from None


def public_url(value):
    require(isinstance(value, str) and 0 < len(value) <= 4096, "INVALID_URL")
    require(not any(ord(char) < 33 or char in "\\<>\"" for char in value), "INVALID_URL_CHARACTERS")
    try:
        parsed = urlsplit(value)
        require(parsed.scheme in ("https", "http"), "PUBLIC_HTTP_URL_REQUIRED")
        require(parsed.username is None and parsed.password is None and "@" not in parsed.netloc,
                "CREDENTIAL_URL_REFUSED")
        host = (parsed.hostname or "").rstrip(".").encode("idna").decode("ascii").lower()
        require(host and "%" not in host and len(host) <= 253, "INVALID_HOST")
        port = parsed.port if parsed.port is not None else (443 if parsed.scheme == "https" else 80)
        require(port == (443 if parsed.scheme == "https" else 80), "NONSTANDARD_PORT_REFUSED")
    except (UnicodeError, ValueError) as error:
        if isinstance(error, BriefingError):
            raise
        raise BriefingError("INVALID_URL") from None
    require(host != "localhost" and not host.endswith((".localhost", ".local", ".internal", ".lan", ".home")),
            "PRIVATE_HOST_REFUSED")
    try:
        address = ipaddress.ip_address(host)
    except ValueError:
        require(re.fullmatch(r"[a-z0-9.-]+", host) is not None and "." in host, "INVALID_PUBLIC_HOST")
    else:
        require(address.is_global and not address.is_multicast, "PRIVATE_ADDRESS_REFUSED")
    require(not any(key.casefold() in SECRET_QUERY_KEYS for key, value in parse_qsl(parsed.query)),
            "CREDENTIAL_QUERY_REFUSED")
    netloc = "[" + host + "]" if ":" in host else host
    path = quote(parsed.path or "/", safe="/%:@!$&'()*+,;=-._~")
    query = quote(parsed.query, safe="%:@!$&'()*+,;=/?-._~")
    return urlunsplit((parsed.scheme, netloc, path, query, ""))


def public_addresses(host, port, timeout=5, resolver=socket.getaddrinfo):
    answers = queue.Queue(maxsize=1)

    def resolve():
        try:
            answers.put((True, resolver(host, port, type=socket.SOCK_STREAM)))
        except OSError:
            answers.put((False, None))

    # getaddrinfo has no portable timeout; a bounded daemon thread cannot hold the CLI open.
    threading.Thread(target=resolve, daemon=True).start()
    try:
        succeeded, records = answers.get(timeout=timeout)
    except queue.Empty:
        raise BriefingError("DNS_TIMEOUT") from None
    require(succeeded and records, "DNS_LOOKUP_FAILED")
    result = []
    for record in records:
        address = ipaddress.ip_address(record[4][0])
        require(address.is_global and not address.is_multicast, "PRIVATE_DNS_ADDRESS_REFUSED")
        if str(address) not in result:
            result.append(str(address))
    return sorted(result, key=lambda value: ":" in value)


class PinnedConnection(http.client.HTTPConnection):
    def __init__(self, host, port, address, secure, timeout):
        super().__init__(host, port, timeout=timeout)
        self.address = address
        self.secure = secure
        self.transport_socket = None

    def connect(self):
        raw = socket.create_connection((self.address, self.port), timeout=self.timeout)
        self.transport_socket = raw
        if self.secure:
            raw = ssl.create_default_context().wrap_socket(raw, server_hostname=self.host)
        self.sock = raw
        self.transport_socket = raw

    def abort(self):
        if self.transport_socket is not None:
            try:
                self.transport_socket.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
        self.close()
        if self.transport_socket is not None:
            self.transport_socket.close()


def fetch_public(url, previous=None, *, timeout=REQUEST_SECONDS, max_bytes=MAX_BODY_BYTES):
    current = public_url(url)
    deadline = time.monotonic() + timeout
    for redirects in range(5):
        require(time.monotonic() < deadline, "FETCH_TIMEOUT")
        parsed = urlsplit(current)
        host = parsed.hostname
        port = 443 if parsed.scheme == "https" else 80
        addresses = public_addresses(host, port, timeout=min(5, max(0.01, deadline - time.monotonic())))
        connection = response = timer = None
        try:
            # Pin the validated IP while retaining original Host and TLS certificate/SNI checks.
            # No proxy, cookies, browser state, netrc, credential or model client is used.
            connection = PinnedConnection(host, port, addresses[0], parsed.scheme == "https",
                                          timeout=min(8, max(0.01, deadline - time.monotonic())))
            timer = threading.Timer(max(0.01, deadline - time.monotonic()), connection.abort)
            timer.daemon = True
            timer.start()
            headers = {"User-Agent": USER_AGENT, "Accept": "text/html,application/atom+xml,application/rss+xml,"
                       "application/feed+json,application/json,application/xml,text/plain;q=0.8", "Accept-Encoding": "identity",
                       "Connection": "close"}
            if previous and previous.get("resolvedUrl") == current:
                for key, field in (("If-None-Match", "etag"), ("If-Modified-Since", "lastModified")):
                    value = previous.get(field)
                    if isinstance(value, str) and len(value) <= 1024 and not any(ord(char) < 32 for char in value):
                        headers[key] = value
            connection.request("GET", urlunsplit(("", "", parsed.path or "/", parsed.query, "")), headers=headers)
            response = connection.getresponse()
            if response.status in (301, 302, 303, 307, 308):
                location = response.getheader("Location")
                require(location is not None, "REDIRECT_WITHOUT_LOCATION")
                following = public_url(urljoin(current, location))
                require(not (parsed.scheme == "https" and urlsplit(following).scheme == "http"),
                        "HTTPS_DOWNGRADE_REFUSED")
                current = following
                continue
            if response.status == 304:
                require(previous is not None and previous.get("resolvedUrl") == current, "UNEXPECTED_NOT_MODIFIED")
                return {"notModified": True, "resolvedUrl": current, "fetchedAt": now_iso()}
            require(response.status == 200, "HTTP_" + str(response.status))
            encoding = (response.getheader("Content-Encoding") or "identity").lower().strip()
            require(encoding in ("identity", ""), "COMPRESSED_RESPONSE_REFUSED")
            content_type = response.getheader("Content-Type") or ""
            mime = content_type.split(";", 1)[0].lower().strip()
            require(mime in {"text/html", "application/xhtml+xml", "application/rss+xml", "application/atom+xml",
                             "application/xml", "text/xml", "text/plain", "application/feed+json", "application/json"},
                    "UNSUPPORTED_CONTENT_TYPE")
            length = response.getheader("Content-Length")
            if length:
                require(length.isdigit() and int(length) <= max_bytes, "RESPONSE_TOO_LARGE")
            chunks, size = [], 0
            while True:
                require(time.monotonic() < deadline, "FETCH_TIMEOUT")
                chunk = response.read1(min(65536, max_bytes + 1 - size))
                if not chunk:
                    break
                chunks.append(chunk)
                size += len(chunk)
                require(size <= max_bytes, "RESPONSE_TOO_LARGE")
            require(length is None or size == int(length), "INCOMPLETE_RESPONSE")
            require(size > 0, "EMPTY_RESPONSE")
            charset = re.search(r"charset\s*=\s*[\"']?([A-Za-z0-9._-]+)", content_type, re.I)
            charset = charset.group(1) if charset else "utf-8"
            try:
                decoded = b"".join(chunks).decode(charset, errors="strict")
            except (LookupError, UnicodeError):
                raise BriefingError("RESPONSE_DECODING_FAILED") from None
            return {"body": decoded, "mime": mime, "resolvedUrl": current, "fetchedAt": now_iso(),
                    "etag": response.getheader("ETag"), "lastModified": response.getheader("Last-Modified")}
        except (OSError, http.client.HTTPException):
            raise BriefingError("FETCH_TIMEOUT" if time.monotonic() >= deadline else "PUBLIC_FETCH_FAILED") from None
        finally:
            if timer:
                timer.cancel()
            if response:
                response.close()
            if connection:
                connection.abort()
    raise BriefingError("TOO_MANY_REDIRECTS")


class MeaningfulHTML(HTMLParser):
    SKIP = {"script", "style", "noscript", "nav", "header", "footer", "aside", "form", "svg", "template"}
    BLOCK = {"p", "div", "li", "br", "h1", "h2", "h3", "h4", "tr", "section", "article", "main"}
    VOID = {"area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr"}

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.stack = []
        self.all_text = []
        self.main_text = []
        self.title_text = []
        self.element_count = 0

    def handle_starttag(self, tag, attributes):
        self.element_count += 1
        require(self.element_count <= 100000 and len(self.stack) < 200, "HTML_COMPLEXITY_LIMIT")
        attributes = dict(attributes)
        skipped = tag in self.SKIP or "hidden" in attributes or attributes.get("aria-hidden") == "true"
        style = (attributes.get("style") or "").replace(" ", "").lower()
        skipped = skipped or "display:none" in style or "visibility:hidden" in style
        main = tag in ("main", "article") or attributes.get("role") == "main"
        self.stack.append((tag, skipped, main))
        if tag in self.BLOCK:
            self.handle_data("\n")
        if tag in self.VOID:
            self.stack.pop()

    def handle_endtag(self, tag):
        if tag in self.BLOCK:
            self.handle_data("\n")
        for index in range(len(self.stack) - 1, -1, -1):
            if self.stack[index][0] == tag:
                self.stack = self.stack[:index]
                break

    def handle_data(self, data):
        if any(tag == "title" for tag, _, _ in self.stack):
            self.title_text.append(data)
            return
        if any(skipped for _, skipped, _ in self.stack):
            return
        self.all_text.append(data)
        if any(main for _, _, main in self.stack):
            self.main_text.append(data)


def normalized_text(value):
    lines = [" ".join(unicodedata.normalize("NFKC", line).split()) for line in value.splitlines()]
    lines = [line for line in lines if line]
    lines = [line for index, line in enumerate(lines) if index == 0 or line != lines[index - 1]]
    result = "\n".join(lines)
    require(result and len(result) <= MAX_TEXT_CHARS and len(lines) <= MAX_TEXT_LINES, "EMPTY_OR_OVERSIZED_TEXT")
    return result


def html_text(value):
    parser = MeaningfulHTML()
    parser.feed(value)
    parser.close()
    text = normalized_text("".join(parser.main_text or parser.all_text))
    title = " ".join("".join(parser.title_text).split())[:300]
    return text, title


def xml_name(tag):
    return tag.rsplit("}", 1)[-1]


def extract_text(body, mime, source_url):
    if mime in ("text/html", "application/xhtml+xml"):
        return html_text(body)
    if mime == "text/plain":
        return normalized_text(body), ""
    if mime in ("application/feed+json", "application/json"):
        try:
            feed = json.loads(body)
        except ValueError:
            raise BriefingError("INVALID_JSON_FEED") from None
        require(isinstance(feed, dict) and isinstance(feed.get("items"), list), "JSON_FEED_REQUIRED")
        entries = []
        for item in feed["items"]:
            require(isinstance(item, dict), "INVALID_JSON_FEED_ITEM")
            content = item.get("content_text") or item.get("summary") or item.get("content_html") or ""
            if item.get("content_html") and not item.get("content_text") and content.strip():
                content = html_text(content)[0]
            entries.append(" | ".join(str(item.get(key) or "") for key in ("title", "url", "date_published"))
                           + " | " + " ".join(str(content).split()))
        return normalized_text("\n".join(sorted(set(entries)))), str(feed.get("title") or "")[:300]
    require("<!DOCTYPE" not in body.upper() and "<!ENTITY" not in body.upper(), "XML_ENTITY_DECLARATION_REFUSED")
    try:
        root = ET.fromstring(body)
    except ET.ParseError:
        raise BriefingError("INVALID_XML_FEED") from None
    require(xml_name(root.tag) in ("rss", "feed", "RDF"), "RSS_OR_ATOM_REQUIRED")
    entries, title = [], ""
    for element in root.iter():
        if xml_name(element.tag) == "title" and not title:
            title = " ".join("".join(element.itertext()).split())[:300]
        if xml_name(element.tag) not in ("item", "entry"):
            continue
        fields = {}
        for child in element:
            name = xml_name(child.tag)
            if name in ("title", "link", "pubDate", "published", "description", "summary", "content", "encoded"):
                value = child.attrib.get("href", "") if name == "link" else ""
                value = value or "".join(child.itertext())
                if name in ("description", "summary", "content", "encoded") and value.strip():
                    value = html_text(value)[0]
                fields.setdefault(name, value)
        content = next((fields.get(key) for key in ("description", "summary", "content", "encoded") if fields.get(key)), "")
        entry = " | ".join((fields.get("title", ""), urljoin(source_url, fields.get("link", "")),
                            fields.get("published", fields.get("pubDate", "")), content))
        entries.append(" ".join(entry.split()))
    require(entries, "FEED_HAS_NO_ENTRIES")
    return normalized_text("\n".join(sorted(set(entries)))), title


def meaningful_diff(before, after):
    old, new = before.splitlines(), after.splitlines()
    diff = list(difflib.unified_diff(old, new, fromfile="previous-success", tofile="current-success", n=1, lineterm=""))
    added = [line[1:] for line in diff if line.startswith("+") and not line.startswith("+++")]
    removed = [line[1:] for line in diff if line.startswith("-") and not line.startswith("---")]
    excerpt = "\n".join(diff)
    return {"addedLines": len(added), "removedLines": len(removed), "addedText": "\n".join(added),
            "excerpt": excerpt[:16000], "truncated": len(excerpt) > 16000}


def importance(diff, mode):
    if mode != "high-impact":
        return {"selected": True, "method": "all-content-changes", "score": None, "reasons": []}
    text = diff["addedText"].casefold()
    rules = [(3, "새 모델·중요 성능 발표 표현", r"(?:introduc\w*|announc\w*|releas\w*|launch\w*|공개|발표|출시).{0,100}(?:model|gpt|claude|gemini|llama|qwen|deepseek|glm|llm|모델)|(?:model|모델).{0,70}(?:release|launch|공개|발표|출시)"),
             (3, "공개 가중치·학습 기술", r"open[- ]weights?|open[- ]source model|가중치 공개|새로운 학습|training breakthrough"),
             (2, "학습·추론 방법", r"reinforcement learning|distillation|speculative decoding|mixture.of.experts|test.time comput|강화학습|증류|추측 디코딩|전문가 혼합"),
             (2, "성능·평가 결과", r"state.of.the.art|breakthrough|benchmark|최고 성능|성능 향상|평가 결과"),
             (2, "가격·추론 효율 변화", r"(?:cost|price|latency|throughput|비용|가격|지연|속도).{0,60}(?:reduc|improv|faster|lower|\d+\s*%|절감|개선|감소|배)"),
             (1, "주요 기능·방법", r"reasoning|multimodal|agentic|long.context|million tokens|추론|멀티모달|에이전트|컨텍스트")]
    matched = [(points, reason) for points, reason, pattern in rules if re.search(pattern, text, re.S)]
    score = sum(points for points, _ in matched)
    return {"selected": score >= 3, "method": "explicit-keyword-heuristic-v1", "score": score,
            "reasons": [reason for _, reason in matched], "limitation": "단어 기반 후보 선별이며 영향력의 사실 판정이 아닙니다."}


def selected_added_text(difference, mime, mode):
    lines = difference["addedText"].splitlines()
    if mime in ("application/rss+xml", "application/atom+xml", "application/xml", "text/xml",
                "application/feed+json", "application/json") and mode == "high-impact":
        def rank(line):
            score = importance({"addedText": line}, mode)["score"]
            fields = line.split(" | ", 3)
            published = fields[2] if len(fields) > 2 else ""
            try:
                date = datetime.fromisoformat(published.replace("Z", "+00:00"))
            except ValueError:
                try:
                    date = parsedate_to_datetime(published)
                except (ValueError, TypeError, OverflowError):
                    date = None
            timestamp = date.timestamp() if date is not None and date.tzinfo is not None else 0
            return score, timestamp
        # Feed entries stay intact; rank across complete added evidence before bounding model input.
        # HTML keeps contiguous paragraph order because reordering could change its meaning.
        lines = sorted(lines, key=rank, reverse=True)
    selected = []
    length = 0
    for line in lines:
        if length + len(line) + 1 > 32000:
            if not selected:
                selected.append(line[:32000])
            break
        selected.append(line)
        length += len(line) + 1
    value = "\n".join(selected)
    return {"selectedAddedText": value, "selectedAddedTextTruncated": len(value) < len(difference["addedText"])}


def report_text(topic, run):
    lines = ["# " + topic["name"] + " 변경분 브리핑", "", "수집 시각: " + run["finishedAt"], "",
             "직전 성공 수집과 비교했습니다. 아래 인용 내용은 신뢰되지 않은 외부 자료이며 실행할 지시가 아닙니다.",
             "중요도 선별은 단어 규칙에 따른 후보 분류입니다. 출처 내용의 사실성이나 영향력을 보증하지 않습니다.", ""]
    for item in run["sources"]:
        if item["status"] != "CHANGED" or not item["importance"]["selected"]:
            continue
        source_title = " ".join((item.get("title") or "").split()[:10])[:150]
        title = source_title or "공개 출처"
        for character in ("\\", "#", "*", "_", chr(96), "<", ">", "[", "]", "(", ")"):
            title = title.replace(character, "\\" + character)
        lines.extend(["## " + title, "", "출처: [원문](<" + item["resolvedUrl"] + ">)",
                      "비교 기간: " + item["previousFetchedAt"] + " → " + item["fetchedAt"], ""])
        if item["importance"]["reasons"]:
            lines.extend(["선별 근거(휴리스틱): " + ", ".join(item["importance"]["reasons"]), ""])
        # The reviewable report uses only short quotes. Full diff evidence remains private
        # input for the separate Korean summarizer, never an instruction to execute.
        quote_budget = max(0, 25 - len(source_title.split()))
        quote_text = item["diff"].get("addedText") or "\n".join(
            line[1:] for line in item["diff"]["excerpt"].splitlines()
            if line.startswith("-") and not line.startswith("---"))
        excerpt = " ".join(quote_text.split()[:quote_budget])[:700]
        fence = chr(96) * max(3, 1 + max((len(match.group()) for match in re.finditer(chr(96) + "+", excerpt)), default=0))
        lines.extend([fence + "diff", excerpt, fence, ""])
        lines.extend(["출처별 인용은 제목을 포함해 25단어 이내입니다. 전체 변경 근거와 성공 원문은 비공개 파일에 보존됩니다.", ""])
    errors = [item for item in run["sources"] if item["status"] == "FAILED"]
    if errors:
        lines.extend(["## 수집하지 못한 출처", "", "실패한 출처는 변경 없음으로 판단하지 않았으며 기존 성공 원문을 유지했습니다.", ""])
        lines.extend("- [출처](<" + item["url"] + ">): " + item["errorCode"] for item in errors)
    return "\n".join(lines) + "\n"


def workflow_publish(state, run, evidence, report):
    helper = Path(__file__).with_name("telegram-task-status.py")
    if not helper.is_file():
        return {"status": "unavailable", "reason": "TASK_RECEIPT_HELPER_NOT_INSTALLED"}
    spec = importlib.util.spec_from_file_location("briefing_task_status", helper)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    module.publish_workflow(state, run["id"], (run["topicName"] + " 변경분 점검")[:120], "briefing",
                            {"status": "failed" if run["failedSources"] else "succeeded", "evidencePath": str(evidence)},
                            {"status": "passed", "evidencePath": str(evidence)},
                            [{"id": "report", "path": str(report)}] if report else [], require_delivery=False)
    return {"status": "published", "workflowId": run["id"]}


def check_topic(root, state, topic, *, fetcher=fetch_public, peek=False, run_id=None):
    run_id = run_id or "briefing-" + uuid.uuid4().hex
    require(re.fullmatch(r"briefing-[a-z0-9-]{6,64}", run_id) is not None, "INVALID_RUN_ID")
    run = {"schemaVersion": 1, "id": run_id, "topicId": topic["id"],
           "topicName": topic["name"], "startedAt": now_iso(), "peek": peek,
           "sourceTextUntrusted": True, "sources": [], "delivery": {"status": "NOT_REQUESTED"}}
    directory = root / "runs" / run["id"]
    private_dir(directory)
    require(not (directory / "result.json").exists(), "COMPLETED_RUN_ALREADY_EXISTS")
    updates = []
    for url in topic["urls"]:
        source_id = sha256_text(url)[:24]
        snapshot_path = root / "snapshots" / topic["id"] / (source_id + ".json")
        item = {"sourceId": source_id, "url": url}
        try:
            old = read_json(snapshot_path)
            if old is not None:
                require(isinstance(old, dict) and old.get("url") == url and isinstance(old.get("text"), str)
                        and old.get("textSha256") == sha256_text(old["text"]), "INVALID_PREVIOUS_SNAPSHOT")
            item["previousTextSha256"] = old["textSha256"] if old else None
            response = fetcher(url, old)
            if response.get("notModified"):
                require(old is not None, "NOT_MODIFIED_WITHOUT_BASELINE")
                new = dict(old, fetchedAt=response["fetchedAt"])
                item.update(status="UNCHANGED", fetchedAt=response["fetchedAt"], resolvedUrl=old["resolvedUrl"])
            else:
                text, title = extract_text(response["body"], response["mime"], response["resolvedUrl"])
                new = {"schemaVersion": 1, "url": url, "resolvedUrl": response["resolvedUrl"],
                       "fetchedAt": response["fetchedAt"], "text": text, "textSha256": sha256_text(text),
                       "title": title, "etag": response.get("etag"), "lastModified": response.get("lastModified")}
                item.update(status="BASELINE" if old is None else "UNCHANGED" if old["textSha256"] == new["textSha256"]
                            else "CHANGED", fetchedAt=new["fetchedAt"], resolvedUrl=new["resolvedUrl"], title=title,
                            textSha256=new["textSha256"])
                if item["status"] == "CHANGED":
                    difference = meaningful_diff(old["text"], text)
                    item.update(previousFetchedAt=old["fetchedAt"], importance=importance(difference, topic["importance"]),
                                diff=dict(difference, addedText=difference["addedText"][:32000],
                                          addedTextTruncated=len(difference["addedText"]) > 32000,
                                          **selected_added_text(difference, response["mime"], topic["importance"])))
            observed = directory / "sources" / (source_id + ".json")
            atomic_json(observed, new)
            item["snapshotPath"] = str(observed)
            item["snapshotSha256"] = hashlib.sha256(observed.read_bytes()).hexdigest()
            item["textSha256"] = new["textSha256"]
            updates.append((snapshot_path, new))
        except (BriefingError, OSError, ValueError, TypeError, KeyError) as error:
            item.update(status="FAILED", attemptedAt=now_iso(),
                        errorCode=str(error) if isinstance(error, BriefingError) else "SOURCE_PROCESSING_FAILED",
                        previousSuccessPreserved=True)
        run["sources"].append(item)
    run.update(finishedAt=now_iso(), failedSources=sum(item["status"] == "FAILED" for item in run["sources"]),
               changedSources=sum(item["status"] == "CHANGED" for item in run["sources"]),
               selectedSources=sum(item["status"] == "CHANGED" and item["importance"]["selected"] for item in run["sources"]))
    run["status"] = "PARTIAL_FAILURE" if run["failedSources"] and updates else "FAILED" if run["failedSources"] else (
        "CHANGED" if run["selectedSources"] else "FILTERED" if run["changedSources"] else
        "BASELINE" if any(item["status"] == "BASELINE" for item in run["sources"]) else "UNCHANGED")
    report = directory / "briefing.md" if run["selectedSources"] else None
    if report:
        atomic_bytes(report, report_text(topic, run).encode())
    run["artifact"] = {"path": str(report), "sha256": hashlib.sha256(report.read_bytes()).hexdigest()} if report else None
    evidence = directory / "result.json"
    atomic_json(evidence, run)
    tracking = workflow_publish(state, run, evidence, report)
    atomic_json(directory / "tracking.json", tracking)
    if not peek:
        commit_run(root, run)
    index = {"id": run["id"], "status": run["status"], "finishedAt": run["finishedAt"], "evidencePath": str(evidence),
             "artifact": run["artifact"], "tracking": tracking, "selectedSources": run["selectedSources"],
             "changedSources": run["changedSources"], "failedSources": run["failedSources"],
             "silent": run["selectedSources"] == 0 and run["failedSources"] == 0}
    atomic_json(root / "latest" / (topic["id"] + ".json"), index)
    return index


def commit_run(root, run):
    """Apply validated successful snapshots after delivery; safe to resume after a partial commit."""
    require(isinstance(run, dict) and re.fullmatch(r"briefing-[a-z0-9-]{6,64}", run.get("id", "")) is not None,
            "INVALID_RUN_ID")
    topic_id = run.get("topicId", "")
    require(re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,47}", topic_id) is not None, "INVALID_TOPIC_ID")
    directory = root / "runs" / run["id"]
    evidence = read_json(directory / "result.json")
    require(evidence == run, "RUN_EVIDENCE_MISMATCH")
    committed = read_json(directory / "baseline-commit.json")
    if committed is not None:
        require(committed.get("runId") == run["id"] and committed.get("status") == "COMMITTED",
                "INVALID_COMMIT_RECEIPT")
        return committed
    changes = []
    for source in run["sources"]:
        if source["status"] == "FAILED":
            continue
        require(source["status"] in ("BASELINE", "UNCHANGED", "CHANGED"), "INVALID_SOURCE_STATUS")
        source_id = sha256_text(source["url"])[:24]
        require(source.get("sourceId") == source_id, "SOURCE_ID_MISMATCH")
        observation = directory / "sources" / (source_id + ".json")
        require(source.get("snapshotPath") == str(observation), "SNAPSHOT_PATH_MISMATCH")
        snapshot = read_json(observation)
        require(hashlib.sha256(observation.read_bytes()).hexdigest() == source.get("snapshotSha256"),
                "OBSERVATION_FILE_HASH_MISMATCH")
        require(isinstance(snapshot, dict) and snapshot.get("url") == source["url"]
                and snapshot.get("fetchedAt") == source["fetchedAt"]
                and snapshot.get("textSha256") == sha256_text(snapshot.get("text", "")),
                "SNAPSHOT_HASH_MISMATCH")
        if source.get("textSha256"):
            require(snapshot["textSha256"] == source["textSha256"], "RUN_SNAPSHOT_HASH_MISMATCH")
        target = root / "snapshots" / topic_id / (source_id + ".json")
        previous = read_json(target)
        if previous is not None:
            require(previous.get("textSha256") == sha256_text(previous.get("text", "")), "BASELINE_HASH_MISMATCH")
        previous_hash = previous.get("textSha256") if previous else None
        if previous_hash == snapshot["textSha256"] and previous["fetchedAt"] >= snapshot["fetchedAt"]:
            continue
        require(previous_hash == source.get("previousTextSha256") or previous_hash == snapshot["textSha256"],
                "BASELINE_CHANGED_SINCE_COLLECTION")
        changes.append((target, snapshot))
    # Validate every source before the first commit. Re-running recognizes already applied sources.
    for path, snapshot in changes:
        atomic_json(path, snapshot)
    receipt = {"schemaVersion": 1, "runId": run["id"], "status": "COMMITTED", "committedAt": now_iso(),
               "successfulSources": sum(source["status"] != "FAILED" for source in run["sources"]),
               "failedSourcesPreserved": run["failedSources"]}
    atomic_json(directory / "baseline-commit.json", receipt)
    return receipt


def load_topics(root):
    config = read_json(root / "topics.json", {"schemaVersion": 1, "topics": {}})
    require(isinstance(config, dict) and config.get("schemaVersion") == 1 and isinstance(config.get("topics"), dict),
            "INVALID_TOPIC_REGISTRY")
    return config


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state-dir", type=Path, default=DEFAULT_STATE)
    parser.add_argument("--json", action="store_true")
    commands = parser.add_subparsers(dest="command", required=True)
    topics = commands.add_parser("topic").add_subparsers(dest="topic_command", required=True)
    add = topics.add_parser("add")
    add.add_argument("--id", required=True)
    add.add_argument("--name", required=True)
    add.add_argument("--url", action="append", required=True)
    add.add_argument("--importance", choices=("all", "high-impact"), default="all")
    listing = topics.add_parser("list")
    listing.add_argument("--include-disabled", action="store_true")
    topics.add_parser("remove").add_argument("id")
    for check in (commands.add_parser("check"), topics.add_parser("check")):
        selection = check.add_mutually_exclusive_group(required=True)
        selection.add_argument("--topic")
        selection.add_argument("--all", action="store_true")
        check.add_argument("--peek", action="store_true", help="collect evidence without advancing comparison baselines")
    args = parser.parse_args()
    os.umask(0o077)
    root = args.state_dir / "operations/workflows/briefing"
    private_dir(root)
    lock_path = root / "registry.lock"
    safe_path(lock_path)
    descriptor = os.open(lock_path, os.O_RDWR | os.O_CREAT, 0o600)
    with os.fdopen(descriptor, "w") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise BriefingError("BRIEFING_CHECK_ALREADY_RUNNING") from None
        config = load_topics(root)
        if args.command == "topic" and args.topic_command == "add":
            require(re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,47}", args.id) is not None, "INVALID_TOPIC_ID")
            require(args.id not in config["topics"], "TOPIC_ID_ALREADY_EXISTS")
            require(0 < len(args.name) <= 120 and not any(ord(char) < 32 for char in args.name), "INVALID_TOPIC_NAME")
            urls = list(dict.fromkeys(public_url(value) for value in args.url))
            require(0 < len(urls) <= MAX_SOURCES, "SOURCE_COUNT_LIMIT")
            topic = {"id": args.id, "name": args.name, "urls": urls, "importance": args.importance,
                     "enabled": True, "createdAt": now_iso(), "schedule": None}
            config["topics"][args.id] = topic
            atomic_json(root / "topics.json", config)
            print(json.dumps({"status": "REGISTERED", "topic": topic, "scheduled": False}, ensure_ascii=False))
        elif args.command == "topic" and args.topic_command == "list":
            print(json.dumps([topic for topic in config["topics"].values() if topic["enabled"] or args.include_disabled],
                             ensure_ascii=False, indent=2))
        elif args.command == "topic" and args.topic_command == "remove":
            require(args.id in config["topics"], "TOPIC_NOT_FOUND")
            config["topics"][args.id].update(enabled=False, disabledAt=now_iso())
            atomic_json(root / "topics.json", config)
            print(json.dumps({"status": "DISABLED", "topicId": args.id, "historyPreserved": True}))
        else:
            selected = [topic for topic in config["topics"].values() if topic["enabled"]
                        and (args.all or topic["id"] == args.topic)]
            require(selected, "NO_ENABLED_TOPIC_SELECTED")
            receipts = [check_topic(root, args.state_dir, topic, peek=args.peek) for topic in selected]
            if args.json:
                print(json.dumps({"checks": receipts}, ensure_ascii=False))
            else:
                for receipt in receipts:
                    if not receipt["silent"]:
                        print(json.dumps(receipt, ensure_ascii=False))
            return 1 if any(receipt["failedSources"] for receipt in receipts) else 0
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (BriefingError, OSError, ValueError, TypeError, KeyError) as error:
        print("FAIL briefing: " + (str(error) if isinstance(error, BriefingError) else "LOCAL_WORKFLOW_FAILED"), file=sys.stderr)
        raise SystemExit(1)
