# AWS SigV4 Test Vectors

These test vectors are reproduced from the AWS Signature Version 4 Test Suite, published by
Amazon Web Services as part of the developer documentation. They are used here under fair-use
as correctness fixtures for the in-tree SigV4 signing implementation.

**AWS source page:**
https://docs.aws.amazon.com/general/latest/gr/signature-v4-test-suite.html

**Mirror used to obtain files:**
https://github.com/mhart/aws4/tree/master/test/aws-sig-v4-test-suite

Each subdirectory is one test case. Files within a case directory:
- `<name>.req`   — raw HTTP request (method, path, headers, body)
- `<name>.creq`  — expected canonical request (SHA-256 is taken of this)
- `<name>.sts`   — expected string-to-sign (HMAC chain is applied to this)
- `<name>.authz` — expected Authorization header value
- `<name>.sreq`  — expected signed request (with Authorization inserted)

All cases use the published test credentials:
- Access key ID: `AKIDEXAMPLE`
- Secret access key: `wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY`
- Region: `us-east-1`
- Service: `service` (literal — not `s3`; the suite is service-agnostic)

Note on `normalize-path`: The AWS S3 specification explicitly states that URI paths must NOT
be normalized for S3 requests, because S3 object keys may contain path segments such as `..`.
This case uses service `service` (not `s3`) and expects normalization. The SigV4 signer
conditionally normalizes only when `service != "s3"`.
