#include "heart-packet.h"
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Foundation.Collections.h>
#include <winrt/Windows.Devices.Bluetooth.Advertisement.h>
#include <winrt/Windows.Devices.Bluetooth.GenericAttributeProfile.h>
#include <winrt/Windows.Storage.Streams.h>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <iostream>
#include <mutex>
#include <set>
#include <string>
#include <thread>
#include <vector>

using namespace winrt;
using namespace Windows::Devices::Bluetooth;
using namespace Windows::Devices::Bluetooth::Advertisement;
using namespace Windows::Devices::Bluetooth::GenericAttributeProfile;
using namespace Windows::Storage::Streams;
using namespace std::chrono_literals;

static void line(const std::string& text) {
    static std::mutex output;
    std::lock_guard lock(output);
    std::cout << text << std::endl;
}

static int scan() {
    BluetoothLEAdvertisementWatcher watcher;
    watcher.ScanningMode(BluetoothLEScanningMode::Active);
    watcher.AdvertisementFilter().Advertisement().ServiceUuids().Append(GattServiceUuids::HeartRate());
    watcher.Received([](auto const&, BluetoothLEAdvertisementReceivedEventArgs const& event) {
        try {
            const auto address = event.BluetoothAddress();
            const bool random = event.BluetoothAddressType() == BluetoothAddressType::Random;
            static std::mutex mutex;
            static std::set<std::pair<uint64_t, bool>> seen;
            std::lock_guard lock(mutex);
            if (!seen.emplace(address, random).second) return;
            auto name = to_string(event.Advertisement().LocalName());
            for (char& ch : name) if (static_cast<unsigned char>(ch) < 32) ch = ' ';
            line("DEVICE\t" + std::to_string(address) + "\t" + (random ? "R" : "P") + "\t" + name);
        } catch (...) {}
    });
    watcher.Start();
    for (int tick = 0; tick < 80; ++tick) {
        if (watcher.Status() == BluetoothLEAdvertisementWatcherStatus::Aborted) return 2;
        std::this_thread::sleep_for(100ms);
    }
    watcher.Stop();
    return 0;
}

static int connect(uint64_t address, BluetoothAddressType type) {
    auto device = BluetoothLEDevice::FromBluetoothAddressAsync(address, type).get();
    if (!device) return 2;
    auto services = device.GetGattServicesForUuidAsync(GattServiceUuids::HeartRate(), BluetoothCacheMode::Uncached).get();
    if (services.Status() != GattCommunicationStatus::Success || !services.Services().Size()) return 2;
    auto service = services.Services().GetAt(0);
    auto chars = service.GetCharacteristicsForUuidAsync(GattCharacteristicUuids::HeartRateMeasurement(), BluetoothCacheMode::Uncached).get();
    if (chars.Status() != GattCommunicationStatus::Success || !chars.Characteristics().Size()) return 2;
    auto measurement = chars.Characteristics().GetAt(0);
    auto handler = measurement.ValueChanged(auto_revoke, [](auto const&, GattValueChangedEventArgs const& args) {
        try {
            auto reader = DataReader::FromBuffer(args.CharacteristicValue());
            std::vector<uint8_t> data(reader.UnconsumedBufferLength());
            reader.ReadBytes(data);
            auto bpm = heartRate(data.data(), data.size());
            line(bpm ? "BPM\t" + std::to_string(*bpm) : "NO_READING");
        } catch (...) {}
    });
    if (measurement.WriteClientCharacteristicConfigurationDescriptorAsync(
        GattClientCharacteristicConfigurationDescriptorValue::Notify).get() != GattCommunicationStatus::Success) return 2;
    line("SUBSCRIBED");
    while (device.ConnectionStatus() == BluetoothConnectionStatus::Connected) std::this_thread::sleep_for(250ms);
    return 2;
}

int main(int argc, char** argv) {
    try {
        init_apartment(apartment_type::multi_threaded);
        // The JVM owns the input pipe. Release the radio even if the client crashes.
        std::thread([] { std::getchar(); std::_Exit(0); }).detach();
        if (argc == 2 && std::string(argv[1]) == "scan") return scan();
        if (argc == 4 && std::string(argv[1]) == "connect") {
            return connect(std::stoull(argv[2]), std::string(argv[3]) == "R" ? BluetoothAddressType::Random : BluetoothAddressType::Public);
        }
    } catch (...) {}
    return 2;
}
