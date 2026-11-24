#include "../../../native/heart-packet.h"
#ifdef NDEBUG
#error The heart packet tests require assertions
#endif
#include <cassert>

int main() {
    constexpr std::uint8_t normal[]{0, 76}, wide[]{1, 3, 1};
    constexpr std::uint8_t unsupported[]{2, 91}, lost[]{4, 90}, worn[]{6, 88};
    assert(!heartRate(nullptr, 0));
    assert(!heartRate(normal, 1));
    assert(heartRate(normal, sizeof normal) == 76u);
    assert(!heartRate(wide, 2));
    assert(heartRate(wide, sizeof wide) == 259u);
    assert(heartRate(unsupported, sizeof unsupported) == 91u);
    assert(!heartRate(lost, sizeof lost));
    assert(heartRate(worn, sizeof worn) == 88u);

    constexpr std::uint8_t energy[]{8, 72, 0x34, 0x12};
    assert(!heartRate(energy, 2));
    assert(!heartRate(energy, 3));
    assert(heartRate(energy, sizeof energy) == 72u);

    constexpr std::uint8_t rr[]{16, 80, 0, 3, 0, 3};
    assert(!heartRate(rr, 2));
    assert(!heartRate(rr, 3));
    assert(heartRate(rr, 4) == 80u);
    assert(!heartRate(rr, 5));
    assert(heartRate(rr, sizeof rr) == 80u);

    constexpr std::uint8_t all[]{31, 3, 1, 2, 0, 8, 4};
    for (std::size_t length = 0; length < sizeof all; ++length)
        assert(!heartRate(all, length));
    assert(heartRate(all, sizeof all) == 259u);
}
