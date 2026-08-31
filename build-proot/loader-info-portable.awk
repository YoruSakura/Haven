function hex_to_dec(value, result, i, digit) {
    result = 0
    value = tolower(value)
    for (i = 1; i <= length(value); i++) {
        digit = index("0123456789abcdef", substr(value, i, 1)) - 1
        if (digit < 0) return 0
        result = result * 16 + digit
    }
    return result
}

$NF == "pokedata_workaround" { pokedata_workaround = hex_to_dec($2) }
$NF == "_start" { start = hex_to_dec($2) }

END {
    print "#include <unistd.h>"
    print "const ssize_t offset_to_pokedata_workaround=" (pokedata_workaround - start) ";"
}
