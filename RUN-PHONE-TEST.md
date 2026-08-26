# Phone / radio live test — ready to run

Echo box: **144.202.13.118** (ewr), tools v0.1.2 on both ends, ufw opened for 51820/51821 udp.
Verified from wired before you switched: 300/300, p50 10.5 ms, min 5.2 ms.

Run these from `E:\dev0-0\2026\8\22\tessera` **after** the laptop is on the phone hotspot.
If the laptop still has ethernet, add `--bind <wifi-adapter-ipv4>` to BOTH arms or it will
quietly ride the wired NIC (that bug cost us a whole rematch once).

```bash
T=tools/build/install/tessera/bin/tessera
IP=144.202.13.118
TOKEN=vSHjg27PALNOdqNdXvk9
PK='FXvXoXt5wFrmB9sj6HWFW5p7dG84hHZDr/VULVFEHgPrec2/2Qh8OYcmxXWfTCzDqkGFuhI55SWThyo2ipxlaXj3W6CxACWKExzTaF6IjFs1RVNoskMkYqmymofySSNH+mVX+If9JEau9KQUNR49+0qwu8NnQGxi5Qh5Uo/EGB5TjMWEGbR/KaQWW7Lbxgd5QIuuYIW0RkaF55SDcMd8eE+3J5YP5G6YUzkKODO3THYZ26jy9LcZky/WPHorfEg9UWOcCVLxfAHMxZACUAcgJZOS8KDK0D8uyy53Fj8dqH/Ia11H4YO/K8erEF1K5DiE+k0S5Lrd9qCVKY0vSMYUYJH4nK6+0afyRlkhWy7rK5VpFph5HE49bDWIRlbuYCnAZ1rRVqfyrJoCggDNlc0YnBjyFgPBmBenqUsGID/TJU9vgHs6hhSNQqf9KDJerHdJ2b/a4h4pEkE9ua6LqG1X55F1l6z6B5+T6scQWiGxmEoVgV4xuXDr5lUkOs6wezwIo1EBW0riQc+zInOjKgrz6IRcg6Jl1mo1nIIStZJemCi7yHdOhkQPml+/hEF9CLhPuQsJDFU1sQEk+WQKEFnT93oLiVM5GQvN9KF8obX3FqHNmVIc52FNRZFSggpK9V5K4pMZSTiIhWMySWs5KzNe6mwoRgJpRlkGWwCuAwHBon0SYm1X2AWuzHbox7kM95MQ8j6WeKeGErKgPJLnQ7m+Z31RNkfGvH01t1KV0nDqwmVhxGdS4xMUpmASUj9FmoBmo2PLcZE442kXBkmpLMMaR0814TtdgEW0x2haqXvpJE8JnMoV9Sd+XHgU+XHXqKT/1IX6Q28EvD4e3IM5/H8H+Go+d6i2xL8+I62pFZMN9BuTdMHzy0NqyV+eRg8CZqzE5l2nS0a4SKzziAN3xi8XWUHB4gK/m4CYk2yKALE2ohdZAX5Yl1DwRL7HO6R+iTDGZoxtto9aYYX8Ohwshm9MhnUdi76y6AWyTAMnHMeQYiI2ZMogpypJwqwfIMYek8HuqSsAzXx1Y21WgZ/Z1btr6G47y1FHOjWHqb2BVDKqiMY98yfm1JemR1Z0SQRqGkxCaUYsO6ydYMsymCd6fKy07ICNiUxOQsPpusYrF2VuRgYO9D81hyQWuVjRqUSA8zdxjFVuzBwyUb509HEkosdUIVLtWk6+xsvPQaiQSbz5NMC3QIUIyamKBJR7tYUWaWjDkhuH0sf9N2URWL4nWUdYDJnnRX8cNK2W95DrCQdKPF93dTzE/ID4BVLJVgeJkcBglYbdZEwpFU6Z57+N9j0nsAEUjA8IKMoQykDigs4mMG47jKmORlaAdsrgrAsgWzjPUH/vIbQstW75CEzHyRVlNbt2ZVwDYMdXa81gWjB/+FzetMTIRGYW+BIOmmq28Z0qA6ro03ABqwN1fGOlKqu6bFB9AGWiCnMbIVmdFrFr4HXtMoIIgHd5UY6zcU9nG4ozzCi8QE3PvFyI1BMFhYF4EAYsM4FgYQht8JCpvGrV8MER1WWMBH4GxU1q2i/8JC73skgnA7grnJAG3A9dekrEZX14o8lZRRIO6j2z8huSNjmjAuAa2LfkYaMoyj/Vu5eAlvZF7oqPy3nbBD8un1/xbw=='

# 1. baseline, the rate that was pristine last time
$T probe --connect $IP:51820 --peer-key "$PK" --token "$TOKEN" --rate 25 --count 300 --size 1200 --out live-results/phone2/t25.csv

# 2. the rate that stressed the uplink last time (this is the one the closed-fix should change)
$T probe --connect $IP:51820 --peer-key "$PK" --token "$TOKEN" --rate 50 --count 300 --size 1200 --out live-results/phone2/t50.csv

# 3. raw-UDP A/B over the identical path — always run adjacent to a tessera arm
$T probe --connect $IP:51821 --transport udp --token x --rate 50 --count 300 --size 1200 --out live-results/phone2/u50.csv

# 4. W2 over the wire: bulk-ish, larger and faster
$T probe --connect $IP:51820 --peer-key "$PK" --token "$TOKEN" --rate 200 --count 2000 --size 1200 --out live-results/phone2/t200.csv
```

Doze / battery (the E5 remainder): run #1, then lock the phone and leave it for a few minutes,
then run #1 again without re-tethering. A connection that survives the doze and one that has to
re-establish are different results, and both are worth having.

Paste the output back and I'll interpret it.
