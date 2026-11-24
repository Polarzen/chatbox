#include "../../../native/heart-packet.h"
#include <cassert>
int main() {
    uint8_t normal[]{0, 76}, wide[]{1, 3, 1}, contact[]{4, 90}, ok[]{6, 88};
    assert(heartRate(normal, 2) == 76);
    assert(heartRate(wide, 3) == 259);
    assert(!heartRate(wide, 2));
    assert(!heartRate(normal, 1));
    assert(!heartRate(contact, 2));
    assert(heartRate(ok, 2) == 88);
}
