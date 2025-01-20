let triggir = env:TRIGGIR_DHALL_BASE_IMPORT_URL

in  triggir.runner "speakeasy-builder" (Some 4000) (Some 4000) (None Text)
