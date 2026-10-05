# nord_forge_license V2

Add before licensed Nord resources:

```cfg
setr nord_license "FG-XXXXXXXX-XXXXXXXX"
setr nord_keymaster "cfxk_your_key"
ensure nord_forge_license
ensure your_resource
```

Server resources can use:

```lua
exports.nord_forge_license:ValidateProduct('product-slug', function(ok, result)
    if not ok then return end
    -- start protected feature
end)
```

The V2 Java platform preserves the legacy `/api/license/validate` and `/api/license/session` contracts for staged migration.
