# Standard EC domain parameter fixtures

Public domain parameters exported with OpenSSL 3.6.4:

```sh
openssl ecparam -name <curve> -param_enc explicit -outform DER -out <curve>.der
```

`prime256v1`, `secp384r1`, `secp521r1` and Brainpool P256/P320/P384/P512 r1 match the analyzer's canonical domain tuples. `brainpoolP256t1` shares the r1 field prime but has different coefficients and generator: it must not be reported as r1.

These files contain no private keys or country evidence. Tests use them only to check explicit-parameter recognition. Unknown parameter sets remain unrecognized.
