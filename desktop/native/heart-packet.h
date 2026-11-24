#pragma once
#include <cstddef>
#include <cstdint>
#include <optional>

inline std::optional<unsigned> heartRate(const std::uint8_t* bytes, std::size_t size) {
    if (size < 2) return {};
    const auto flags = bytes[0];
    if ((flags & 6) == 4) return {};  // Contact sensing supported, no skin contact.
    const bool wide = (flags & 1) != 0;
    const std::size_t fixedSize = 1 + (wide ? 2 : 1) + ((flags & 8) ? 2 : 0);
    if (size < fixedSize) return {};
    const auto remaining = size - fixedSize;
    if ((flags & 16) && (remaining < 2 || remaining % 2 != 0)) return {};
    return wide ? bytes[1] | (unsigned(bytes[2]) << 8) : bytes[1];
}
