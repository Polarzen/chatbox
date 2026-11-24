#pragma once
#include <cstddef>
#include <cstdint>
#include <optional>

inline std::optional<unsigned> heartRate(const std::uint8_t* bytes, std::size_t size) {
    if (size < 2) return {};
    const auto flags = bytes[0];
    if ((flags & 6) == 4) return {};  // Contact sensing supported, no skin contact.
    if (!(flags & 1)) return bytes[1];
    if (size < 3) return {};
    return bytes[1] | (unsigned(bytes[2]) << 8);
}
