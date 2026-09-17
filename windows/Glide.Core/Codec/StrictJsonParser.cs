using System.Text.Json.Nodes;

namespace Glide.Core.Codec;

public sealed class StrictJsonParser
{
    private readonly string _text;
    private readonly int _maxDepth;
    private int _index;
    private int _currentDepth;

    public StrictJsonParser(string text, int maxDepth = 8)
    {
        _text = text ?? throw new ArgumentNullException(nameof(text));
        _maxDepth = maxDepth;
        _index = 0;
        _currentDepth = 0;
    }

    public JsonObject Parse()
    {
        SkipWhitespace();
        if (_index >= _text.Length)
        {
            throw new FormatException("Unexpected end of JSON input: payload is empty");
        }

        var element = ParseElement();
        SkipWhitespace();
        if (_index < _text.Length)
        {
            throw new FormatException($"Trailing characters after root JSON element at index {_index}");
        }

        if (element is not JsonObject jsonObject)
        {
            throw new FormatException($"Root JSON payload must be an object, but got {element?.GetType().Name ?? "null"}");
        }

        return jsonObject;
    }

    private JsonNode? ParseElement()
    {
        SkipWhitespace();
        if (_index >= _text.Length)
        {
            throw new FormatException($"Unexpected end of JSON input at index {_index}");
        }

        char c = _text[_index];
        return c switch
        {
            '{' => ParseObject(),
            '[' => ParseArray(),
            '"' => JsonValue.Create(ParseStringLiteral()),
            't' or 'f' => JsonValue.Create(ParseBoolean()),
            'n' => ParseNull(),
            '-' or (>= '0' and <= '9') => ParseNumber(),
            _ => throw new FormatException($"Unexpected character '{c}' at index {_index}")
        };
    }

    private JsonObject ParseObject()
    {
        Expect('{');
        _currentDepth++;
        if (_currentDepth > _maxDepth)
        {
            throw new FormatException($"JSON nesting depth limit of {_maxDepth} exceeded (depth: {_currentDepth})");
        }

        var obj = new JsonObject();
        var seenKeys = new HashSet<string>(StringComparer.Ordinal);

        SkipWhitespace();
        if (Peek() == '}')
        {
            Expect('}');
            _currentDepth--;
            return obj;
        }

        while (true)
        {
            SkipWhitespace();
            if (Peek() != '"')
            {
                throw new FormatException($"Expected string object key at index {_index}");
            }

            string key = ParseStringLiteral();
            if (!seenKeys.Add(key))
            {
                throw new FormatException($"Duplicate JSON key '{key}' detected in object");
            }

            SkipWhitespace();
            Expect(':');
            var value = ParseElement();
            obj.Add(key, value);

            SkipWhitespace();
            char next = Peek();
            if (next == '}')
            {
                Expect('}');
                break;
            }
            if (next == ',')
            {
                Expect(',');
            }
            else
            {
                throw new FormatException($"Expected ',' or '}}' in object at index {_index}, found '{next}'");
            }
        }

        _currentDepth--;
        return obj;
    }

    private JsonArray ParseArray()
    {
        Expect('[');
        _currentDepth++;
        if (_currentDepth > _maxDepth)
        {
            throw new FormatException($"JSON nesting depth limit of {_maxDepth} exceeded (depth: {_currentDepth})");
        }

        var array = new JsonArray();
        SkipWhitespace();
        if (Peek() == ']')
        {
            Expect(']');
            _currentDepth--;
            return array;
        }

        while (true)
        {
            var value = ParseElement();
            array.Add(value);

            SkipWhitespace();
            char next = Peek();
            if (next == ']')
            {
                Expect(']');
                break;
            }
            if (next == ',')
            {
                Expect(',');
            }
            else
            {
                throw new FormatException($"Expected ',' or ']' in array at index {_index}, found '{next}'");
            }
        }

        _currentDepth--;
        return array;
    }

    private string ParseStringLiteral()
    {
        Expect('"');
        var sb = new System.Text.StringBuilder();
        while (_index < _text.Length)
        {
            char c = _text[_index++];
            if (c == '"')
            {
                return sb.ToString();
            }
            if (c == '\\')
            {
                if (_index >= _text.Length)
                {
                    throw new FormatException("Unterminated escape sequence in string");
                }
                char esc = _text[_index++];
                switch (esc)
                {
                    case '"': sb.Append('"'); break;
                    case '\\': sb.Append('\\'); break;
                    case '/': sb.Append('/'); break;
                    case 'b': sb.Append('\b'); break;
                    case 'f': sb.Append('\f'); break;
                    case 'n': sb.Append('\n'); break;
                    case 'r': sb.Append('\r'); break;
                    case 't': sb.Append('\t'); break;
                    case 'u':
                        if (_index + 4 > _text.Length)
                        {
                            throw new FormatException("Incomplete unicode escape in string");
                        }
                        string hex = _text.Substring(_index, 4);
                        _index += 4;
                        sb.Append((char)Convert.ToInt32(hex, 16));
                        break;
                    default:
                        throw new FormatException($"Invalid escape character '\\{esc}'");
                }
            }
            else
            {
                sb.Append(c);
            }
        }
        throw new FormatException("Unclosed string literal");
    }

    private bool ParseBoolean()
    {
        if (_index + 4 <= _text.Length && _text.Substring(_index, 4) == "true")
        {
            _index += 4;
            return true;
        }
        if (_index + 5 <= _text.Length && _text.Substring(_index, 5) == "false")
        {
            _index += 5;
            return false;
        }
        throw new FormatException($"Invalid boolean at index {_index}");
    }

    private JsonNode? ParseNull()
    {
        if (_index + 4 <= _text.Length && _text.Substring(_index, 4) == "null")
        {
            _index += 4;
            return null;
        }
        throw new FormatException($"Invalid null token at index {_index}");
    }

    private JsonNode ParseNumber()
    {
        int start = _index;
        if (_text[_index] == '-') _index++;
        if (_index >= _text.Length) throw new FormatException("Invalid number at end of input");

        while (_index < _text.Length && char.IsDigit(_text[_index]))
        {
            _index++;
        }

        bool isFloating = false;
        if (_index < _text.Length && _text[_index] == '.')
        {
            isFloating = true;
            _index++;
            while (_index < _text.Length && char.IsDigit(_text[_index]))
            {
                _index++;
            }
        }

        if (_index < _text.Length && (_text[_index] == 'e' || _text[_index] == 'E'))
        {
            isFloating = true;
            _index++;
            if (_index < _text.Length && (_text[_index] == '+' || _text[_index] == '-')) _index++;
            while (_index < _text.Length && char.IsDigit(_text[_index]))
            {
                _index++;
            }
        }

        string numStr = _text.Substring(start, _index - start);
        if (isFloating)
        {
            if (double.TryParse(numStr, System.Globalization.CultureInfo.InvariantCulture, out double dVal))
            {
                return JsonValue.Create(dVal);
            }
        }
        else
        {
            if (long.TryParse(numStr, System.Globalization.CultureInfo.InvariantCulture, out long lVal))
            {
                return JsonValue.Create(lVal);
            }
            if (System.Numerics.BigInteger.TryParse(numStr, System.Globalization.CultureInfo.InvariantCulture, out var biVal))
            {
                return JsonValue.Create(biVal) ?? throw new FormatException($"Failed to create JSON number for '{numStr}'");
            }
        }

        throw new FormatException($"Invalid number format: '{numStr}'");
    }

    private void Expect(char expected)
    {
        SkipWhitespace();
        if (_index >= _text.Length)
        {
            throw new FormatException($"Expected '{expected}' but found end of input");
        }
        char actual = _text[_index++];
        if (actual != expected)
        {
            throw new FormatException($"Expected '{expected}' but found '{actual}' at index {_index - 1}");
        }
    }

    private char Peek()
    {
        SkipWhitespace();
        if (_index >= _text.Length)
        {
            throw new FormatException("Unexpected end of input");
        }
        return _text[_index];
    }

    private void SkipWhitespace()
    {
        while (_index < _text.Length && char.IsWhiteSpace(_text[_index]))
        {
            _index++;
        }
    }
}
