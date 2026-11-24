#include "heart-packet.h"
#include <windows.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Foundation.Collections.h>
#include <winrt/Windows.Devices.Bluetooth.Advertisement.h>
#include <winrt/Windows.Devices.Bluetooth.GenericAttributeProfile.h>
#include <winrt/Windows.Storage.Streams.h>
#include <chrono>
#include <iostream>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

using namespace winrt;
using namespace Windows::Devices::Bluetooth;
using namespace Windows::Devices::Bluetooth::Advertisement;
using namespace Windows::Devices::Bluetooth::GenericAttributeProfile;
using namespace Windows::Storage::Streams;

static void line(const std::string& text) {
    static std::mutex output;
    std::lock_guard lock(output);
    std::cout << text << std::endl;
}

static int failure(const char* stage) {
    line(std::string("ERROR\t") + stage);
    return 2;
}

static bool stopping(HANDLE stop) {
    return WaitForSingleObject(stop, 0) == WAIT_OBJECT_0;
}

template<typename Operation>
static auto awaitResult(const Operation& operation, HANDLE stop) {
    while (operation.Status() == Windows::Foundation::AsyncStatus::Started) {
        if (WaitForSingleObject(stop, 50) == WAIT_OBJECT_0) {
            operation.Cancel();
            throw hresult_canceled();
        }
    }
    return operation.GetResults();
}

struct AdvertisementInfo {
    bool heartRate = false;
    std::string name;
};

struct ScanState {
    std::mutex mutex;
    std::map<std::pair<uint64_t, BluetoothAddressType>, AdvertisementInfo> devices;
    BluetoothError error = BluetoothError::OtherError;
    handle stopped{check_pointer(CreateEventW(nullptr, TRUE, FALSE, nullptr))};
};

static int scan(HANDLE stop) {
    auto state = std::make_shared<ScanState>();
    BluetoothLEAdvertisementWatcher watcher;
    watcher.ScanningMode(BluetoothLEScanningMode::Active);
    // A scan response can carry the name separately from the service UUID.
    auto received = watcher.Received(auto_revoke, [state](auto const&, BluetoothLEAdvertisementReceivedEventArgs const& event) {
        try {
            const auto address = event.BluetoothAddress();
            const auto type = event.BluetoothAddressType();
            if (type != BluetoothAddressType::Random && type != BluetoothAddressType::Public) return;
            const auto advertisement = event.Advertisement();
            uint32_t index;
            const bool heartRate = advertisement.ServiceUuids().IndexOf(GattServiceUuids::HeartRate(), index);
            auto name = to_string(advertisement.LocalName());
            if (!heartRate && name.empty()) return;
            for (char& ch : name) if (static_cast<unsigned char>(ch) < 32) ch = ' ';

            std::lock_guard lock(state->mutex);
            auto& device = state->devices[{address, type}];
            const bool newName = name.size() > device.name.size();
            const bool discovered = heartRate && !device.heartRate;
            if (newName) device.name = std::move(name);
            device.heartRate |= heartRate;
            if (device.heartRate && (discovered || newName)) {
                line("DEVICE\t" + std::to_string(address) + "\t" +
                     (type == BluetoothAddressType::Random ? "R" : "P") + "\t" + device.name);
            }
        } catch (...) {
            // A bad advertisement need not invalidate other devices in this scan.
        }
    });
    auto stopped = watcher.Stopped(auto_revoke, [state](auto const&, BluetoothLEAdvertisementWatcherStoppedEventArgs const& event) {
        std::lock_guard lock(state->mutex);
        try { state->error = event.Error(); } catch (...) {}
        SetEvent(state->stopped.get());
    });

    watcher.Start();
    HANDLE events[]{stop, state->stopped.get()};
    auto result = WaitForMultipleObjects(2, events, FALSE, 8000);
    if (result != WAIT_OBJECT_0 + 1) {
        watcher.Stop();
        if (result == WAIT_OBJECT_0) return 0;
        result = WaitForMultipleObjects(2, events, FALSE, 2000);
        if (result == WAIT_OBJECT_0) return 0;
        if (result != WAIT_OBJECT_0 + 1) return failure("SCAN");
    }
    std::lock_guard lock(state->mutex);
    switch (state->error) {
    case BluetoothError::Success:
        return 0;
    case BluetoothError::RadioNotAvailable:
    case BluetoothError::DisabledByPolicy:
    case BluetoothError::DisabledByUser:
    case BluetoothError::NotSupported:
    case BluetoothError::TransportNotSupported:
        return failure("RADIO");
    default:
        return failure("SCAN");
    }
}

static int connect(uint64_t address, BluetoothAddressType type, HANDLE stop, const char*& stage) {
    auto device = awaitResult(BluetoothLEDevice::FromBluetoothAddressAsync(address, type), stop);
    if (!device) return failure(stage);
    stage = "SERVICE";
    auto services = awaitResult(device.GetGattServicesForUuidAsync(GattServiceUuids::HeartRate(), BluetoothCacheMode::Uncached), stop);
    if (services.Status() != GattCommunicationStatus::Success || !services.Services().Size()) return failure(stage);
    auto service = services.Services().GetAt(0);
    stage = "CHARACTERISTIC";
    auto chars = awaitResult(service.GetCharacteristicsForUuidAsync(GattCharacteristicUuids::HeartRateMeasurement(), BluetoothCacheMode::Uncached), stop);
    if (chars.Status() != GattCommunicationStatus::Success || !chars.Characteristics().Size()) return failure(stage);
    auto measurement = chars.Characteristics().GetAt(0);
    stage = "SUBSCRIBE";
    auto handler = measurement.ValueChanged(auto_revoke, [](auto const&, GattValueChangedEventArgs const& args) {
        try {
            auto reader = DataReader::FromBuffer(args.CharacteristicValue());
            std::vector<uint8_t> data(reader.UnconsumedBufferLength());
            reader.ReadBytes(data);
            auto bpm = heartRate(data.data(), data.size());
            if (bpm) {
                const auto received = winrt::clock::to_sys(args.Timestamp());
                const auto epochMs = std::chrono::duration_cast<std::chrono::milliseconds>(received.time_since_epoch()).count();
                line("BPM\t" + std::to_string(*bpm) + "\t" + std::to_string(epochMs));
            } else {
                line("NO_READING");
            }
        } catch (...) {
            line("NO_READING");
        }
    });
    if (awaitResult(measurement.WriteClientCharacteristicConfigurationDescriptorAsync(
            GattClientCharacteristicConfigurationDescriptorValue::Notify), stop) != GattCommunicationStatus::Success)
        return failure(stage);
    line("SUBSCRIBED");
    stage = "DEVICE";
    while (WaitForSingleObject(stop, 250) == WAIT_TIMEOUT) {
        if (device.ConnectionStatus() == BluetoothConnectionStatus::Disconnected) {
            line("DISCONNECTED");
            return 2;
        }
    }
    return 0;
}

static int run(int argc, char** argv, HANDLE stop) {
    bool scanning = argc == 2 && std::string(argv[1]) == "scan";
    const char* stage = scanning ? "SCAN" : "DEVICE";
    try {
        init_apartment(apartment_type::multi_threaded);
        struct ApartmentCleanup { ~ApartmentCleanup() { uninit_apartment(); } } apartment;
        if (scanning) return scan(stop);
        if (argc == 4 && std::string(argv[1]) == "connect") {
            return connect(std::stoull(argv[2]), std::string(argv[3]) == "R" ? BluetoothAddressType::Random : BluetoothAddressType::Public,
                           stop, stage);
        }
    } catch (...) {
        if (stopping(stop)) return 0;
        return failure(stage);
    }
    return 2;
}

static bool stopRequested(HANDLE input) {
    if (GetFileType(input) != FILE_TYPE_PIPE) return WaitForSingleObject(input, 0) != WAIT_TIMEOUT;
    DWORD available = 0;
    return !PeekNamedPipe(input, nullptr, 0, nullptr, &available, nullptr) || available != 0;
}

int main(int argc, char** argv) {
    handle stop{CreateEventW(nullptr, TRUE, FALSE, nullptr)};
    if (!stop) return 2;
    const HANDLE input = GetStdHandle(STD_INPUT_HANDLE);
    int result = 2;
    std::thread worker([&] { result = run(argc, argv, stop.get()); });
    while (WaitForSingleObject(worker.native_handle(), 50) == WAIT_TIMEOUT) {
        if (stopRequested(input)) {
            SetEvent(stop.get());
            // A stuck Bluetooth driver must not keep the helper alive after its owner exits.
            if (WaitForSingleObject(worker.native_handle(), 2000) == WAIT_TIMEOUT)
                TerminateProcess(GetCurrentProcess(), 0);
            break;
        }
    }
    worker.join();
    return result;
}
