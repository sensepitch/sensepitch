wget -O google/googlebot.json https://developers.google.com/static/search/apis/ipranges/googlebot.json
wget -O google/special-crawlers.json https://developers.google.com/static/search/apis/ipranges/special-crawlers.json
wget -O google/user-triggered-fetchers.json https://developers.google.com/static/search/apis/ipranges/user-triggered-fetchers.json
wget -O google/user-triggered-fetchers-google.json https://developers.google.com/static/search/apis/ipranges/user-triggered-fetchers-google.json

# Amazonbot. The developer page embeds the JSON inside a <pre><code> block, so the HTML
# has to be scraped. Entries are single addresses without a prefix length.
wget -O amazonbot.html https://developer.amazon.com/amazonbot/ip-addresses/
python3 - <<'PYEOF'
import json
import re
import sys

html = open('amazonbot.html').read()
match = re.search(r'<pre><code class="container">(.*?)</code></pre>', html, re.S)
if match is None:
    sys.exit('Amazonbot page: <pre><code> block not found')
json.dump(json.loads(match.group(1)), open('amazon/amazonbot.json', 'w'), indent=2)
PYEOF
rm amazonbot.html
