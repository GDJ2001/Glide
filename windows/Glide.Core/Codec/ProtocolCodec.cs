using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using Glide.Core.Models;
using Glide.Core.Security;

namespace Glide.Core.Codec;

public static class ProtocolCodec
{
    public const int MaxControlBytes = 16384;
    public const int MaxExtensionsBytes = 1024;
    public const int MaxJsonDepth = 8;
    public const int ProtocolMajorVersion = 1;

    private const long MaxItemBytes = 68_719_476_736L; // 64 GiB
    private const long MaxBatchBytes = 274_877_906_944L; // 256 GiB
    private static readonly Regex MimeRegex = new(@"^[a-zA-Z0-9!#$&^_.+-]+/[a-zA-Z0-9!#$&^_.+-]+$", RegexOptions.Compiled);

    public static byte[] Encode(Envelope envelope)
    {
        if (envelope.V != ProtocolMajorVersion)
        {
            throw new ProtocolViolationException(
                WireErrorCodes.UnsupportedProtocol,
                $"Unsupported protocol version {envelope.V}, expected {ProtocolMajorVersion}");
        }

        if (envelope.Seq < 1)
        {
            throw new ProtocolViolationException(
                WireErrorCodes.MalformedMessage,
                $"Envelope seq must be positive (seq >= 1), got: {envelope.Seq}");
        }

        if (envelope.Extensions != null)
        {
            var extBytes = Encoding.UTF8.GetBytes(envelope.Extensions.ToJsonString());
            if (extBytes.Length > MaxExtensionsBytes)
            {
                throw new ProtocolViolationException(
                    WireErrorCodes.LimitExceeded,
                    $"Extensions size {extBytes.Length} bytes exceeds maximum {MaxExtensionsBytes} bytes");
            }
        }

        var root = new JsonObject
        {
            ["v"] = envelope.V,
            ["type"] = envelope.Type,
            ["sessionId"] = envelope.SessionId.Value,
            ["messageId"] = envelope.MessageId.Value,
            ["seq"] = envelope.Seq,
            ["body"] = JsonNode.Parse(envelope.Body.ToJsonString())
        };

        if (envelope.Extensions != null)
        {
            root["extensions"] = JsonNode.Parse(envelope.Extensions.ToJsonString());
        }

        var json = root.ToJsonString();
        var bytes = Encoding.UTF8.GetBytes(json);
        if (bytes.Length > MaxControlBytes)
        {
            throw new ProtocolViolationException(
                WireErrorCodes.LimitExceeded,
                $"Control payload size {bytes.Length} bytes exceeds maximum {MaxControlBytes} bytes");
        }

        return bytes;
    }

    public static Envelope Decode(byte[] bytes)
    {
        if (bytes.Length > MaxControlBytes)
        {
            throw new ProtocolViolationException(
                WireErrorCodes.LimitExceeded,
                $"Control payload size {bytes.Length} bytes exceeds maximum {MaxControlBytes} bytes");
        }

        if (bytes.Length >= 3 && bytes[0] == 0xEF && bytes[1] == 0xBB && bytes[2] == 0xBF)
        {
            throw new ProtocolViolationException(
                WireErrorCodes.MalformedMessage,
                "UTF-8 Byte Order Mark (BOM) is strictly prohibited");
        }

        string text;
        try
        {
            var utf8Strict = new UTF8Encoding(encoderShouldEmitUTF8Identifier: false, throwOnInvalidBytes: true);
            text = utf8Strict.GetString(bytes);
        }
        catch (Exception ex)
        {
            throw new ProtocolViolationException(
                WireErrorCodes.MalformedMessage,
                "Malformed UTF-8 sequence in control payload", ex);
        }

        JsonObject root;
        try
        {
            root = new StrictJsonParser(text, maxDepth: MaxJsonDepth).Parse();
        }
        catch (Exception ex)
        {
            throw new ProtocolViolationException(
                WireErrorCodes.MalformedMessage,
                $"Failed to parse JSON: {ex.Message}", ex);
        }

        foreach (var (key, _) in root)
        {
            if (!ProtocolCatalog.AllowedEnvelopeKeys.Contains(key))
            {
                throw new ProtocolViolationException(
                    WireErrorCodes.MalformedMessage,
                    $"Unknown envelope field: '{key}'");
            }
        }

        if (!root.TryGetPropertyValue("v", out var vElem) || vElem == null)
            throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, "Missing envelope field 'v'");
        if (!root.TryGetPropertyValue("type", out var typeElem) || typeElem == null)
            throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, "Missing envelope field 'type'");
        if (!root.TryGetPropertyValue("sessionId", out var sessionIdElem) || sessionIdElem == null)
            throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, "Missing envelope field 'sessionId'");
        if (!root.TryGetPropertyValue("messageId", out var messageIdElem) || messageIdElem == null)
            throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, "Missing envelope field 'messageId'");
        if (!root.TryGetPropertyValue("seq", out var seqElem) || seqElem == null)
            throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, "Missing envelope field 'seq'");
        if (!root.TryGetPropertyValue("body", out var bodyElem) || bodyElem == null)
            throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, "Missing envelope field 'body'");

        root.TryGetPropertyValue("extensions", out var extensionsElem);

        if (vElem is not JsonValue vVal || vVal.GetValue<object>() is string)
        {
            throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, "Envelope field 'v' must be a JSON integer");
        }
        if (!int.TryParse(vVal.ToString(), out int v))
        {
            throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, $"Envelope field 'v' is not an integer: '{vVal}'");
        }
        if (v != ProtocolMajorVersion)
        {
            throw new ProtocolViolationException(WireErrorCodes.UnsupportedProtocol, $"Unsupported protocol major version {v}, expected {ProtocolMajorVersion}");
        }

        if (typeElem is not JsonValue typeVal || typeVal.GetValue<object>() is not string typeStr)
        {
            throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, "Envelope field 'type' must be a string");
        }
        if (!MessageTypes.All.Contains(typeStr))
        {
            throw new ProtocolViolationException(WireErrorCodes.UnsupportedProtocol, $"Unknown protocol message type '{typeStr}'");
        }

        if (sessionIdElem is not JsonValue sessVal || sessVal.GetValue<object>() is not string sessStr)
        {
            throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, "Envelope field 'sessionId' must be a string");
        }
        if (!IdentifierValidation.IsValidUuidV4(sessStr))
        {
            throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, $"sessionId must be a canonical lowercase UUIDv4, got: '{sessStr}'");
        }
        var sessionId = new SessionId(sessStr);

        if (messageIdElem is not JsonValue msgVal || msgVal.GetValue<object>() is not string msgStr)
        {
            throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, "Envelope field 'messageId' must be a string");
        }
        if (!IdentifierValidation.IsValidUuidV4(msgStr))
        {
            throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, $"messageId must be a canonical lowercase UUIDv4, got: '{msgStr}'");
        }
        var messageId = new MessageId(msgStr);

        if (seqElem is not JsonValue seqVal || seqVal.GetValue<object>() is string)
        {
            throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, "Envelope field 'seq' must be a JSON integer");
        }
        if (!long.TryParse(seqVal.ToString(), out long seq))
        {
            throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, $"Envelope field 'seq' is not an integer: '{seqVal}'");
        }
        if (seq < 1)
        {
            throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, $"Envelope field 'seq' must be a positive integer (seq >= 1), got: {seq}");
        }

        if (bodyElem is not JsonObject bodyObj)
        {
            throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, "Envelope field 'body' must be a JSON object");
        }

        JsonObject? extensions = null;
        if (extensionsElem != null)
        {
            if (extensionsElem is not JsonObject extObj)
            {
                throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, "Envelope field 'extensions' must be a JSON object if present");
            }
            var extBytes = Encoding.UTF8.GetBytes(extObj.ToJsonString());
            if (extBytes.Length > MaxExtensionsBytes)
            {
                throw new ProtocolViolationException(
                    WireErrorCodes.LimitExceeded,
                    $"Extensions size {extBytes.Length} bytes exceeds maximum {MaxExtensionsBytes} bytes");
            }
            extensions = extObj;
        }

        ValidateBody(typeStr, bodyObj);

        return new Envelope(v, typeStr, sessionId, messageId, seq, bodyObj, extensions);
    }

    private static void ValidateBody(string type, JsonObject body)
    {
        if (ProtocolCatalog.AllowedBodyKeys.TryGetValue(type, out var allowedKeys))
        {
            foreach (var (key, _) in body)
            {
                if (!allowedKeys.Contains(key))
                {
                    throw new ProtocolViolationException(
                        WireErrorCodes.MalformedMessage,
                        $"Unknown field '{key}' in body for message type '{type}'");
                }
            }
        }

        switch (type)
        {
            case MessageTypes.Hello: ValidateHello(body); break;
            case MessageTypes.HelloAck: ValidateHelloAck(body); break;
            case MessageTypes.OfferBegin: ValidateOfferBegin(body); break;
            case MessageTypes.OfferPage: ValidateOfferPage(body); break;
            case MessageTypes.OfferEnd: ValidateOfferEnd(body); break;
            case MessageTypes.OfferDecision: ValidateOfferDecision(body); break;
            case MessageTypes.ItemStart: ValidateItemStart(body); break;
            case MessageTypes.ItemReady: ValidateItemReady(body); break;
            case MessageTypes.ItemEnd: ValidateItemEnd(body); break;
            case MessageTypes.ItemResult: ValidateItemResult(body); break;
            case MessageTypes.BatchEnd: ValidateBatchEnd(body); break;
            case MessageTypes.BatchResult: ValidateBatchResult(body); break;
            case MessageTypes.Cancel: ValidateCancel(body); break;
            case MessageTypes.CancelAck: ValidateCancelAck(body); break;
            case MessageTypes.Error: ValidateError(body); break;
            case MessageTypes.Ping: ValidatePing(body); break;
            case MessageTypes.Pong: ValidatePong(body); break;
            case MessageTypes.IdentityChallenge: ValidateIdentityChallenge(body); break;
            case MessageTypes.IdentityResponse: ValidateIdentityResponse(body); break;
            case MessageTypes.ResumeRequest: ValidateResumeRequest(body); break;
            case MessageTypes.ResumeAccept: ValidateResumeAccept(body); break;
            case MessageTypes.ResumeReject: ValidateResumeReject(body); break;
        }
    }

    private static void ValidateHello(JsonObject body)
    {
        if (!body.TryGetPropertyValue("supportedMajors", out var majorsElem) || majorsElem is not JsonArray majors || majors.Count == 0)
        {
            throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, "HELLO requires non-empty 'supportedMajors' array");
        }
        foreach (var m in majors)
        {
            if (m is not JsonValue val || val.GetValue<object>() is string || !int.TryParse(val.ToString(), out _))
            {
                throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, "Major version must be integer");
            }
        }

        if (!body.TryGetPropertyValue("requiredCapabilities", out var reqElem) || reqElem is not JsonArray reqCaps)
        {
            throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, "HELLO requires 'requiredCapabilities' array");
        }
        foreach (var c in reqCaps)
        {
            if (c is not JsonValue val || val.GetValue<object>() is not string)
            {
                throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, "Capability must be string");
            }
        }

        if (body.TryGetPropertyValue("optionalCapabilities", out var optElem) && optElem is JsonArray optCaps)
        {
            foreach (var c in optCaps)
            {
                if (c is not JsonValue val || val.GetValue<object>() is not string)
                {
                    throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, "Capability must be string");
                }
            }
        }

        string role = RequireString(body, "role");
        if (role != "sender" && role != "receiver")
        {
            throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, $"HELLO role must be 'sender' or 'receiver', got: '{role}'");
        }

        string peerId = RequireString(body, "ephemeralPeerId");
        if (!IdentifierValidation.IsValidUuidV4(peerId))
        {
            throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, $"HELLO ephemeralPeerId must be UUIDv4, got: '{peerId}'");
        }

        string displayName = RequireString(body, "displayName");
        ValidateDisplayName(displayName);
    }

    private static void ValidateHelloAck(JsonObject body)
    {
        if (!body.TryGetPropertyValue("selectedMajor", out var majElem) || majElem is not JsonValue majVal || majVal.GetValue<object>() is string || !int.TryParse(majVal.ToString(), out int major) || major != ProtocolMajorVersion)
        {
            throw new ProtocolViolationException(WireErrorCodes.UnsupportedProtocol, $"HELLO_ACK selectedMajor must be {ProtocolMajorVersion}");
        }

        if (!body.TryGetPropertyValue("capabilities", out var capsElem) || capsElem is not JsonArray caps)
        {
            throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, "HELLO_ACK requires 'capabilities' array");
        }
        foreach (var c in caps)
        {
            if (c is not JsonValue val || val.GetValue<object>() is not string)
            {
                throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, "Capability must be string");
            }
        }

        string peerId = RequireString(body, "ephemeralPeerId");
        if (!IdentifierValidation.IsValidUuidV4(peerId))
        {
            throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, $"HELLO_ACK ephemeralPeerId must be UUIDv4, got: '{peerId}'");
        }

        string displayName = RequireString(body, "displayName");
        ValidateDisplayName(displayName);

        if (!body.TryGetPropertyValue("limits", out var limitsElem) || limitsElem is not JsonObject limits)
        {
            throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, "HELLO_ACK requires 'limits' object");
        }
        foreach (var (key, _) in limits)
        {
            if (!ProtocolCatalog.AllowedLimitsKeys.Contains(key))
            {
                throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, $"Unknown field '{key}' in limits object");
            }
        }

        if (!limits.TryGetPropertyValue("maxItems", out var maxItemsElem) || maxItemsElem is not JsonValue maxItemsVal || maxItemsVal.GetValue<object>() is string || !int.TryParse(maxItemsVal.ToString(), out int maxItems))
        {
            throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, "limits requires integer 'maxItems'");
        }
        if (maxItems is < 1 or > 1000)
        {
            throw new ProtocolViolationException(WireErrorCodes.LimitExceeded, $"limits 'maxItems' must be in 1..1000, got: {maxItems}");
        }

        long maxItemBytes = RequireByteCount(limits, "maxItemBytes", MaxItemBytes);
        if (maxItemBytes <= 0) throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, "limits 'maxItemBytes' must be positive");

        long maxBatchBytes = RequireByteCount(limits, "maxBatchBytes", MaxBatchBytes);
        if (maxBatchBytes <= 0) throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, "limits 'maxBatchBytes' must be positive");

        if (!limits.TryGetPropertyValue("maxControlBytes", out var maxCtrlElem) || maxCtrlElem is not JsonValue maxCtrlVal || maxCtrlVal.GetValue<object>() is string || !int.TryParse(maxCtrlVal.ToString(), out int maxCtrl) || maxCtrl is < 1024 or > MaxControlBytes)
        {
            throw new ProtocolViolationException(WireErrorCodes.UnsupportedProtocol, "limits 'maxControlBytes' must be in 1024..16384");
        }
    }

    private static void ValidateOfferBegin(JsonObject body)
    {
        RequireUuid(body, "batchId");
        if (!body.TryGetPropertyValue("itemCount", out var countElem) || countElem is not JsonValue countVal || countVal.GetValue<object>() is string || !int.TryParse(countVal.ToString(), out int itemCount) || itemCount is < 1 or > 1000)
        {
            throw new ProtocolViolationException(WireErrorCodes.LimitExceeded, "OFFER_BEGIN 'itemCount' must be in 1..1000");
        }
        RequireByteCount(body, "totalBytes", MaxBatchBytes);
        if (!body.TryGetPropertyValue("pageCount", out var pageElem) || pageElem is not JsonValue pageVal || pageVal.GetValue<object>() is string || !int.TryParse(pageVal.ToString(), out int pageCount) || pageCount is < 1 or > 1000)
        {
            throw new ProtocolViolationException(WireErrorCodes.LimitExceeded, "OFFER_BEGIN 'pageCount' must be in 1..1000");
        }
    }

    private static void ValidateOfferPage(JsonObject body)
    {
        RequireUuid(body, "batchId");
        if (!body.TryGetPropertyValue("pageIndex", out var idxElem) || idxElem is not JsonValue idxVal || idxVal.GetValue<object>() is string || !int.TryParse(idxVal.ToString(), out int pageIdx) || pageIdx < 0)
        {
            throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, "OFFER_PAGE 'pageIndex' must be non-negative integer");
        }

        if (!body.TryGetPropertyValue("items", out var itemsElem) || itemsElem is not JsonArray items || items.Count is < 1 or > 32)
        {
            throw new ProtocolViolationException(WireErrorCodes.LimitExceeded, "OFFER_PAGE items count must be in 1..32");
        }

        foreach (var itemNode in items)
        {
            if (itemNode is not JsonObject itemObj)
            {
                throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, "Item in OFFER_PAGE must be JSON object");
            }
            foreach (var (key, _) in itemObj)
            {
                if (!ProtocolCatalog.AllowedItemKeys.Contains(key))
                {
                    throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, $"Unknown field '{key}' in OFFER_PAGE item");
                }
            }

            RequireUuid(itemObj, "itemId");
            string name = RequireString(itemObj, "name");
            int cpCount = name.EnumerateRunes().Count();
            int nameBytes = Encoding.UTF8.GetByteCount(name);
            if (cpCount is < 1 or > 255 || nameBytes > 1024)
            {
                throw new ProtocolViolationException(WireErrorCodes.LimitExceeded, $"Item name must be 1..255 code points and <= 1024 bytes, got {cpCount} cps, {nameBytes} bytes");
            }
            if (name.Contains('/') || name.Contains('\\') || name.Contains('\0'))
            {
                throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, $"Item name cannot contain path separators or null bytes: '{name}'");
            }

            string mime = RequireString(itemObj, "mime");
            if (mime.Length > 127 || !MimeRegex.IsMatch(mime))
            {
                throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, $"Item mime is invalid: '{mime}'");
            }

            long itemSize =
                RequireByteCount(
                    itemObj,
                    "sizeBytes",
                    MaxItemBytes);

            bool hasRoot =
                itemObj.TryGetPropertyValue(
                    "rootHash",
                    out JsonNode? rootNode) &&
                rootNode != null;
            bool hasChunkSize =
                itemObj.TryGetPropertyValue(
                    "chunkSize",
                    out JsonNode? chunkNode) &&
                chunkNode != null;

            if (hasRoot != hasChunkSize)
            {
                throw new ProtocolViolationException(
                    WireErrorCodes.MalformedMessage,
                    "OFFER_PAGE resume metadata requires both rootHash and chunkSize");
            }

            if (hasRoot)
            {
                if (rootNode is not JsonValue rootValue ||
                    rootValue.GetValue<object>() is not string rootHash ||
                    rootHash.Length != 64 ||
                    rootHash.Any(
                        ch =>
                            !(ch is >= '0' and <= '9') &&
                            !(ch is >= 'a' and <= 'f')))
                {
                    throw new ProtocolViolationException(
                        WireErrorCodes.MalformedMessage,
                        "OFFER_PAGE rootHash must be canonical lowercase SHA-256");
                }

                if (chunkNode is not JsonValue chunkValue ||
                    chunkValue.GetValue<object>() is string ||
                    !int.TryParse(
                        chunkValue.ToString(),
                        out int chunkSize))
                {
                    throw new ProtocolViolationException(
                        WireErrorCodes.MalformedMessage,
                        "OFFER_PAGE chunkSize must be an integer");
                }

                if (chunkSize is < 1 or > 16 * 1024 * 1024)
                {
                    throw new ProtocolViolationException(
                        WireErrorCodes.LimitExceeded,
                        "OFFER_PAGE chunkSize must be in 1..16 MiB");
                }

                if (itemSize == 0)
                {
                    throw new ProtocolViolationException(
                        WireErrorCodes.MalformedMessage,
                        "Zero-byte item cannot carry resume metadata");
                }
            }

            if (itemObj.TryGetPropertyValue("relativePath", out var relElem) && relElem != null)
            {
                if (relElem is not JsonValue relVal || relVal.GetValue<object>() is not string relPath)
                {
                    throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, "Item relativePath must be string");
                }
                Storage.WindowsPathSanitizer.ValidateRelativePath(relPath);
            }
        }
    }

    private static void ValidateOfferEnd(JsonObject body) => RequireUuid(body, "batchId");

    private static void ValidateOfferDecision(JsonObject body)
    {
        RequireUuid(body, "batchId");
        if (!body.TryGetPropertyValue("accepted", out var accElem) || accElem is not JsonValue accVal || accVal.GetValue<object>() is not bool accepted)
        {
            throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, "OFFER_DECISION requires 'accepted' boolean");
        }

        body.TryGetPropertyValue("code", out var codeElem);
        string? code = codeElem is JsonValue cv && cv.GetValue<object>() is string cs ? cs : null;

        if (!accepted)
        {
            if (code == null)
            {
                throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, "OFFER_DECISION rejection requires 'code'");
            }
            HashSet<string> validCodes = ["USER_REJECTED", "INSUFFICIENT_STORAGE", "DESTINATION_UNAVAILABLE"];
            if (!validCodes.Contains(code))
            {
                throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, $"Invalid OFFER_DECISION rejection code: '{code}'");
            }
        }
        else
        {
            if (code != null)
            {
                throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, "OFFER_DECISION acceptance must not contain 'code'");
            }
        }
    }

    private static void ValidateItemStart(JsonObject body)
    {
        RequireUuid(body, "batchId");
        RequireUuid(body, "itemId");
        string bulkRef = RequireString(body, "bulkRef");
        if (string.IsNullOrWhiteSpace(bulkRef) || bulkRef.Length > 64)
        {
            throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, "ITEM_START 'bulkRef' must be 1..64 characters");
        }
        RequireByteCount(body, "sizeBytes", MaxItemBytes);
    }

    private static void ValidateItemReady(JsonObject body)
    {
        RequireUuid(body, "batchId");
        RequireUuid(body, "itemId");
    }

    private static void ValidateItemEnd(JsonObject body)
    {
        RequireUuid(body, "batchId");
        RequireUuid(body, "itemId");
        RequireByteCount(body, "actualBytes", MaxItemBytes);
        string sha256 = RequireString(body, "sha256");
        if (!IdentifierValidation.IsValidSha256(sha256))
        {
            throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, "ITEM_END 'sha256' must be 64 lowercase hex characters");
        }
    }

    private static void ValidateItemResult(JsonObject body)
    {
        RequireUuid(body, "batchId");
        RequireUuid(body, "itemId");
        string status = RequireString(body, "status");
        if (status == "verified")
        {
            RequireByteCount(body, "sizeBytes", MaxItemBytes);
            string sha256 = RequireString(body, "sha256");
            if (!IdentifierValidation.IsValidSha256(sha256))
            {
                throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, "ITEM_RESULT verified 'sha256' must be 64 lowercase hex characters");
            }
            if (body.ContainsKey("code"))
            {
                throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, "ITEM_RESULT verified must not contain 'code'");
            }
        }
        else if (status == "failed")
        {
            string code = RequireString(body, "code");
            if (!WireErrorCodes.All.Contains(code))
            {
                throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, $"ITEM_RESULT failed 'code' must be a valid catalog code, got: '{code}'");
            }
            if (body.ContainsKey("sizeBytes") || body.ContainsKey("sha256"))
            {
                throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, "ITEM_RESULT failed must not contain sizeBytes or sha256");
            }
        }
        else
        {
            throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, $"ITEM_RESULT status must be 'verified' or 'failed', got: '{status}'");
        }
    }

    private static void ValidateBatchEnd(JsonObject body) => RequireUuid(body, "batchId");

    private static void ValidateBatchResult(JsonObject body)
    {
        RequireUuid(body, "batchId");
        string status = RequireString(body, "status");
        if (status != "completed")
        {
            throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, $"BATCH_RESULT status must be 'completed', got: '{status}'");
        }
        if (!body.TryGetPropertyValue("verifiedCount", out var vcElem) || vcElem is not JsonValue vcVal || vcVal.GetValue<object>() is string || !int.TryParse(vcVal.ToString(), out int verifiedCount) || verifiedCount is < 1 or > 1000)
        {
            throw new ProtocolViolationException(WireErrorCodes.LimitExceeded, "BATCH_RESULT 'verifiedCount' must be in 1..1000");
        }
        RequireByteCount(body, "totalVerifiedBytes", MaxBatchBytes);
    }

    private static void ValidateCancel(JsonObject body)
    {
        if (body.ContainsKey("batchId")) RequireUuid(body, "batchId");
        string code = RequireString(body, "code");
        if (code != "USER_CANCELLED" && code != "SYSTEM_CANCELLED")
        {
            throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, $"CANCEL code must be USER_CANCELLED or SYSTEM_CANCELLED, got: '{code}'");
        }
    }

    private static void ValidateCancelAck(JsonObject body) => RequireUuid(body, "replyTo");

    private static void ValidateError(JsonObject body)
    {
        string code = RequireString(body, "code");
        if (!WireErrorCodes.All.Contains(code))
        {
            throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, $"ERROR code '{code}' is not in catalog");
        }
        if (body.ContainsKey("batchId")) RequireUuid(body, "batchId");
        if (body.ContainsKey("itemId")) RequireUuid(body, "itemId");
        string detail = RequireString(body, "detail");
        if (detail.Length > 160)
        {
            throw new ProtocolViolationException(WireErrorCodes.LimitExceeded, $"ERROR detail length must be <= 160 characters, got: {detail.Length}");
        }
    }

    private static void ValidatePing(JsonObject body) => RequireUuid(body, "nonce");
    private static void ValidatePong(JsonObject body) => RequireUuid(body, "nonce");

    private static void ValidateIdentityChallenge(JsonObject body)
    {
        RequireProfile(body, IdentityResumeTranscript.IdentityProfile);
        RequireLowerHex64(body, "challengeNonceHex");
        RequireLowerHex64(body, "verifierFingerprintHex");

        string verifierRole = RequireRole(body, "verifierRole");
        string signerRole = RequireRole(body, "signerRole");
        if (string.Equals(verifierRole, signerRole, StringComparison.Ordinal))
        {
            throw new ProtocolViolationException(
                WireErrorCodes.MalformedMessage,
                "IDENTITY_CHALLENGE roles must be opposite");
        }

        RequireLowerHex64(body, "channelBindingHex");
        RequireLowerHex64(body, "capabilityHashHex");
    }

    private static void ValidateIdentityResponse(JsonObject body)
    {
        RequireProfile(body, IdentityResumeTranscript.IdentityProfile);
        RequireUuid(body, "challengeMessageId");
        RequireLowerHex64(body, "challengeNonceHex");
        RequireLowerHex64(body, "signerFingerprintHex");
        RequireEvenLowerHex(body, "publicKeyDerHex", 4096);
        RequireEvenLowerHex(body, "signatureHex", 1024);
    }

    private static void ValidateResumeRequest(JsonObject body)
    {
        RequireProfile(body, IdentityResumeTranscript.ResumeProfile);
        RequireUuid(body, "originalSessionId");
        RequireUuid(body, "itemId");
        RequireLowerHex64(body, "requesterFingerprintHex");
        RequireLowerHex64(body, "responderFingerprintHex");

        string requesterRole = RequireRole(body, "requesterRole");
        string responderRole = RequireRole(body, "responderRole");
        if (requesterRole != "receiver" || responderRole != "sender")
        {
            throw new ProtocolViolationException(
                WireErrorCodes.MalformedMessage,
                "RESUME_REQUEST must be receiver -> sender");
        }

        long verifiedChunkCount =
            RequireLong(body, "verifiedChunkCount", min: 0);
        long contiguousByteOffset =
            RequireByteCount(body, "contiguousByteOffset", MaxItemBytes);

        if (!body.TryGetPropertyValue("chunkSize", out JsonNode? chunkSizeElement) ||
            chunkSizeElement is not JsonValue chunkSizeValue ||
            chunkSizeValue.GetValue<object>() is string ||
            !int.TryParse(chunkSizeValue.ToString(), out int chunkSize))
        {
            throw new ProtocolViolationException(
                WireErrorCodes.MalformedMessage,
                "RESUME_REQUEST 'chunkSize' must be an integer");
        }

        if (chunkSize is < 1 or > 16_777_216)
        {
            throw new ProtocolViolationException(
                WireErrorCodes.LimitExceeded,
                "RESUME_REQUEST 'chunkSize' must be in 1..16777216");
        }

        long totalSizeBytes =
            RequireByteCount(body, "totalSizeBytes", MaxItemBytes);
        if (contiguousByteOffset > totalSizeBytes)
        {
            throw new ProtocolViolationException(
                WireErrorCodes.MalformedMessage,
                "RESUME_REQUEST offset exceeds totalSizeBytes");
        }

        if (contiguousByteOffset % chunkSize != 0 &&
            contiguousByteOffset != totalSizeBytes)
        {
            throw new ProtocolViolationException(
                WireErrorCodes.MalformedMessage,
                "RESUME_REQUEST offset is not chunk-aligned");
        }

        long expectedChunkCount =
            contiguousByteOffset == totalSizeBytes &&
            totalSizeBytes % chunkSize != 0
                ? checked((totalSizeBytes + chunkSize - 1L) / chunkSize)
                : contiguousByteOffset / chunkSize;

        if (verifiedChunkCount != expectedChunkCount)
        {
            throw new ProtocolViolationException(
                WireErrorCodes.MalformedMessage,
                "RESUME_REQUEST verifiedChunkCount does not match offset/chunkSize");
        }

        RequireLowerHex64(body, "chunkRootHashHex");
        RequireLowerHex64(body, "sessionNonceHex");
        RequireLowerHex64(body, "channelBindingHex");
        RequireLowerHex64(body, "capabilityHashHex");
        RequireEvenLowerHex(body, "signatureHex", 1024);
    }

    private static void ValidateResumeAccept(JsonObject body)
    {
        RequireProfile(body, IdentityResumeTranscript.ResumeProfile);
        RequireUuid(body, "requestMessageId");
        RequireUuid(body, "originalSessionId");
        RequireUuid(body, "itemId");
        RequireLong(body, "startChunkIndex", min: 0);
        RequireByteCount(body, "startByteOffset", MaxItemBytes);
    }

    private static void ValidateResumeReject(JsonObject body)
    {
        RequireProfile(body, IdentityResumeTranscript.ResumeProfile);
        RequireUuid(body, "requestMessageId");
        RequireUuid(body, "originalSessionId");
        RequireUuid(body, "itemId");

        string code = RequireString(body, "code");
        if (!ResumeRejectCodes.All.Contains(code))
        {
            throw new ProtocolViolationException(
                WireErrorCodes.MalformedMessage,
                $"Unknown RESUME_REJECT code '{code}'");
        }
    }

    private static void RequireProfile(JsonObject body, string expected)
    {
        string profile = RequireString(body, "profile");
        if (!string.Equals(profile, expected, StringComparison.Ordinal))
        {
            throw new ProtocolViolationException(
                WireErrorCodes.UnsupportedProtocol,
                $"Unsupported extension profile '{profile}', expected '{expected}'");
        }
    }

    private static string RequireRole(JsonObject body, string key)
    {
        string role = RequireString(body, key);
        if (role is not ("sender" or "receiver"))
        {
            throw new ProtocolViolationException(
                WireErrorCodes.MalformedMessage,
                $"{key} must be 'sender' or 'receiver'");
        }
        return role;
    }

    private static string RequireLowerHex64(JsonObject body, string key)
    {
        string value = RequireString(body, key);
        if (value.Length != 64 ||
            value.Any(static ch =>
                ch is not (>= '0' and <= '9') &&
                ch is not (>= 'a' and <= 'f')))
        {
            throw new ProtocolViolationException(
                WireErrorCodes.MalformedMessage,
                $"{key} must be exactly 64 lowercase hexadecimal characters");
        }
        return value;
    }

    private static string RequireEvenLowerHex(
        JsonObject body,
        string key,
        int maxChars)
    {
        string value = RequireString(body, key);
        if (value.Length == 0 ||
            value.Length > maxChars ||
            value.Length % 2 != 0 ||
            value.Any(static ch =>
                ch is not (>= '0' and <= '9') &&
                ch is not (>= 'a' and <= 'f')))
        {
            throw new ProtocolViolationException(
                WireErrorCodes.MalformedMessage,
                $"{key} must be non-empty even-length lowercase hexadecimal (max {maxChars} chars)");
        }
        return value;
    }

    private static long RequireLong(JsonObject obj, string key, long min = 0)
    {
        if (!obj.TryGetPropertyValue(key, out var elem) || elem is not JsonValue val || val.GetValue<object>() is string || !long.TryParse(val.ToString(), out long value) || value < min)
        {
            throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, $"Field '{key}' must be an integer >= {min}");
        }
        return value;
    }

    private static string RequireString(JsonObject obj, string key)
    {
        if (!obj.TryGetPropertyValue(key, out var elem) || elem is not JsonValue val || val.GetValue<object>() is not string str)
        {
            throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, $"Missing or invalid required string field '{key}'");
        }
        return str;
    }

    private static void RequireUuid(JsonObject obj, string key)
    {
        string val = RequireString(obj, key);
        if (!IdentifierValidation.IsValidUuidV4(val))
        {
            throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, $"Field '{key}' must be a canonical lowercase UUIDv4, got: '{val}'");
        }
    }

    private static long RequireByteCount(JsonObject obj, string key, long maxAllowed)
    {
        string text = RequireString(obj, key);
        long val;
        try
        {
            val = DecimalByteCount.Parse(text);
        }
        catch (Exception ex)
        {
            throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, $"Invalid byte count for '{key}': {ex.Message}", ex);
        }
        if (val > maxAllowed)
        {
            throw new ProtocolViolationException(WireErrorCodes.LimitExceeded, $"Byte count for '{key}' ({val}) exceeds maximum {maxAllowed}");
        }
        return val;
    }

    private static void ValidateDisplayName(string displayName)
    {
        int codePoints = displayName.EnumerateRunes().Count();
        int bytes = Encoding.UTF8.GetByteCount(displayName);
        if (codePoints is < 1 or > 64 || bytes > 256)
        {
            throw new ProtocolViolationException(WireErrorCodes.LimitExceeded, $"displayName must be 1..64 code points and <= 256 UTF-8 bytes, got {codePoints} cps, {bytes} bytes");
        }
    }

    public static byte[] EncodeFramed(Envelope envelope)
    {
        byte[] payload = Encode(envelope);
        int len = payload.Length;
        byte[] buffer = new byte[4 + len];
        buffer[0] = (byte)(len >> 24);
        buffer[1] = (byte)(len >> 16);
        buffer[2] = (byte)(len >> 8);
        buffer[3] = (byte)len;
        Array.Copy(payload, 0, buffer, 4, len);
        return buffer;
    }

    public static Envelope DecodeFramed(Stream stream)
    {
        byte[] header = new byte[4];
        int read = 0;
        while (read < 4)
        {
            int r = stream.Read(header, read, 4 - read);
            if (r <= 0)
            {
                if (read == 0) throw new EndOfStreamException("End of stream reached");
                throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, $"Truncated length prefix in stream: read {read} bytes");
            }
            read += r;
        }

        int len = ((header[0] & 0xFF) << 24) |
                  ((header[1] & 0xFF) << 16) |
                  ((header[2] & 0xFF) << 8) |
                  (header[3] & 0xFF);

        if (len <= 0)
        {
            throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, $"Framed message length must be positive, got: {len}");
        }
        if (len > MaxControlBytes)
        {
            throw new ProtocolViolationException(WireErrorCodes.LimitExceeded, $"Framed control payload length {len} exceeds maximum {MaxControlBytes} bytes");
        }

        byte[] payload = new byte[len];
        read = 0;
        while (read < len)
        {
            int r = stream.Read(payload, read, len - read);
            if (r <= 0)
            {
                throw new ProtocolViolationException(WireErrorCodes.MalformedMessage, $"Stream truncated: expected {len} bytes, got {read}");
            }
            read += r;
        }

        return Decode(payload);
    }
}
