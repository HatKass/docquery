#!/bin/sh
# Starts a single-node SeaweedFS with its S3 gateway on :8333.
# Credentials come from the environment (S3_ACCESS_KEY / S3_SECRET_KEY) and are
# written to a runtime-only config file, so nothing secret lives in the repo.
set -eu

mkdir -p /data /etc/seaweedfs/runtime
cat > /etc/seaweedfs/runtime/s3.json <<JSON
{
  "identities": [
    {
      "name": "docquery",
      "credentials": [
        { "accessKey": "${S3_ACCESS_KEY}", "secretKey": "${S3_SECRET_KEY}" }
      ],
      "actions": ["Admin", "Read", "List", "Tagging", "Write"]
    }
  ]
}
JSON

exec weed server \
  -dir=/data \
  -ip.bind=0.0.0.0 \
  -master.volumeSizeLimitMB=1024 \
  -volume.max=0 \
  -s3 \
  -s3.port=8333 \
  -s3.config=/etc/seaweedfs/runtime/s3.json
