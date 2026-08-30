#!/bin/bash
# Shaped rows only, with TSO/GSO off on the pusher NIC: the TCP flavor of the GSO landmine - a TSO superpacket
# is ONE unit to netem's loss chain, so shaped-TCP rows without this are not the modelled per-packet process.
T=/opt/t2/bin/tessera
K="nlOFDbNwhFcUF9sZN4DkFY8Q391mvxVAnEL/bGFnLyjCrK0AQl2EoBz8LF/vwyzL58RzaIGIpSZ7k831WlQHRZOu83aiAJH1CFY8lY/FNWK3lypN9pLyFRn4FnNiAGpkML4VaHAphhom4AwUDDUBujpaQi6hgQAtB3i4smS8IW/1BA6rtaKo+CCXA6vfaKNoljo1y4h0e2TdhA8ipS2X1g5ns3Mxya4CWsqosUFnjJeIwi5TagBFcQDatn21GH08cwrk4AHS4ovEa71++7gAUI1LiwjIK8AdhK9E5KG11CX900rpoBgfzEr8I6UXWYeiwXX5pw0gQEWjY0RXBiEd0sma1ItaUVDkFcffG3QlYCX99cTs8D4xiVjceJBpE4EB9GHOwn6N2impQoFjBZV16GFh6SkrQsZTg0m1CNAfZxmeC7yZ2oZs2bv6oojuEYmH08vCsKByFWhQS3IOpjeLYLklQJOKfE9ls5AsQzm11ak3IFmy83fzIGY+VQPD+5vv9AFCosdbrLAQ9yk0qWQrUE1hQV31asvAenb8Yo0452yosVszeiT/CTyWQ3RLdAXAM8mj1j5pxb9YBRmjlYu/E5EgIi8Y5TJamYJKxcBAWEthy33Hw5tUAxbkHKyPiaOTZHxa6MPu07+xYo9gWzJwAC4OdyoEm5HOCQFePJu2R7lzxGJ81lL6OmwQ8UuRFhzGKR0nwLSXgKnOOA8ZYcH4ibk/VVUfEWyBZnNyySg/dY2yYz+D54O9ynIywsOfCj97IEqUGYUvWQmt5Db8Sqot6TGVkYyDGzkq57sbIl9tApOd+Ud+hg7lwogV2FYZJGbgiimLWCVNur4YmQGx8WACfHXyG473A3nBoRL7+cTjmUdB8cRXsc7/YXODFm02ikh3kVoKZ3a/+l3O8cnaCWNVg7xtmHWwaTsj2ppAhKHfUMkBBHOqWY9MRCTcnJnsEUVMO48C+L5TNWrf7La9Q2/iu2tfqRUOWbJNjH6+4bAAIUKLPCJw2Dj1VhwD0rPCO8dT+7J49K2OCCtzkiHJuz3wQTlGxsjIIgXANSZO+LKhVrymaW9l4zlaC8fkcA5UaF74h5NH23aUUWJDRY67cpuuZKuNiEINQmFWxXcrVp/CLDGSoTEZjM2cx7FqWBSyIxUAlZFzNwOBLKEK8GefBio1tVVj+ZbKqnNBoyU1W8ua8cDetiy29FIjgx8k9JJRdcSv2stYq8L0bJcU9MakKAscoRwmBThrV4tRaWZXuctqFqm/QTvo2QeOZgl9Cj2iHHO8u3hzvISQ5V7QGnTg16VwoqZoEUI721jQykv60QOO8gJhWLvbijt/8HnQObYmx80RhcFqOiGuZTxY22uKMjT9VwPsm7fMUKrRRKdMvJtVAK5yzAqcOgxqPMPjcEUrWF8QqbH50mA/Rhw8SyXIVLUWHCr8KzpSowg3OrmKIogEWU79/MXADMdzsokZ0EE5xTuAIKUWdz0AFq2tEYRvxUigVBBunJixAqey7MVRVycarCcid7WNcZym56wZQodudb1rjM4LVqWmFhbaW8meMCe3ww6cQU8KuFYUqIc9AVshOi7UmU8GLDOyD2mG0OOB6w/zhll5VFSQXwDvUkf74TlFZg=="
: > /root/bulkvs3.out
ethtool -K enp1s0 tso off gso off tx-udp-segmentation off 2>/dev/null
tc qdisc del dev enp1s0 root 2>/dev/null
tc qdisc add dev enp1s0 root handle 1: prio bands 3
tc qdisc add dev enp1s0 parent 1:3 handle 30: netem loss gemodel 1% 20% limit 1000
tc qdisc add dev enp1s0 parent 30: handle 31: tbf rate 30mbit burst 64kb limit 1500000
for port in 51820 51821; do
  tc filter add dev enp1s0 protocol ip parent 1:0 prio 1 u32 match ip protocol 17 0xff match ip dport $port 0xffff flowid 1:3
  tc filter add dev enp1s0 protocol ip parent 1:0 prio 1 u32 match ip protocol 6 0xff match ip dport $port 0xffff flowid 1:3
done
(setsid nohup sh -c 'sleep 2400; tc qdisc del dev enp1s0 root # TESSERA_SHAPE_WD' >/dev/null 2>&1 &)
for rep in 1 2; do
  timeout 480 $T bulkpush --connect 45.63.29.123:51820 --peer-key "$K" --token bk2 --arm tessera --mb 20 2>&1 | grep bulkpush | sed "s/^/noTSO-rep$rep /" >> /root/bulkvs3.out
  timeout 900 $T bulkpush --connect 45.63.29.123:51821 --token bk2 --arm tls --mb 20 2>&1 | grep bulkpush | sed "s/^/noTSO-rep$rep /" >> /root/bulkvs3.out
done
tc qdisc del dev enp1s0 root 2>/dev/null
ethtool -K enp1s0 tso on gso on 2>/dev/null
echo DONE >> /root/bulkvs3.out
