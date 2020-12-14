#!/usr/bin/env bash
set -euo pipefail

#!/bin/bash
# William Lam
# www.virtuallyghetto.com


OVTOOL=$(command -v ovftool)
OVF=/BACKUPDISK/vmexports/uaw8/uaw8.ovf

ESXI_HOST=10.10.10.26
ESXI_USERNAME=root
ESXI_PASSWORD=4734_systems

VMNAME=uaw8-clone
HOSTNAME=uaw81.atea.dev
IP=10.66.8.85
NETMASK=255.255.0.0
GATEWAY=10.66.66.2
DNS=10.66.66.66
NETWORK="Atea"
DATASTORE=ssd26-12T

### DO NOT EDIT BEYOND HERE ###

"${OVTOOL}" --acceptAllEulas --skipManifestCheck --X:injectOvfEnv --powerOn \
  "--net:Network 1=${NETWORK}" --datastore=${DATASTORE} --diskMode=thin \
  --name=${VMNAME} --prop:hostname=${HOSTNAME} --prop:dns=${DNS} \
  --prop:gateway=${GATEWAY} --prop:ip=${IP} --prop:netmask=${NETMASK} \
  ${VCSA_OVA} "vi://${ESXI_USERNAME}:${ESXI_PASSWORD}@${ESXI_HOST}/"
