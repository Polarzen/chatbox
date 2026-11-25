#include <windows.h>
#include <roapi.h>
#include <string_view>
#include <winrt/Windows.Data.Xml.Dom.h>
#include <winrt/Windows.UI.Notifications.h>

using namespace winrt::Windows::Data::Xml::Dom;
using namespace winrt::Windows::UI::Notifications;

static HRESULT showReplyToast(const wchar_t* uri) {
    XmlDocument xml;
    xml.LoadXml(LR"(<toast activationType="protocol"><visual><binding template="ToastGeneric"><text>Chat Box · 回复已完成</text><text>点击查看回复</text></binding></visual></toast>)");
    xml.DocumentElement().SetAttribute(L"launch", uri);
    auto notifier = ToastNotificationManager::CreateToastNotifier(L"Polarzen.Chatbox");
    // Windows applies notification preferences on Show. Setting() can fail before the first delivery.
    notifier.Show(ToastNotification(xml));
    return S_OK;
}

extern "C" __declspec(dllexport) HRESULT ChatboxShowReplyToast(const wchar_t* uri) noexcept {
    if (!uri || !std::wstring_view(uri).starts_with(L"polarzen-chatbox://reply/")) return E_INVALIDARG;

    const auto apartment = RoInitialize(RO_INIT_MULTITHREADED);
    if (FAILED(apartment) && apartment != RPC_E_CHANGED_MODE) return apartment;
    HRESULT result;
    try {
        result = showReplyToast(uri);
    } catch (...) {
        result = winrt::to_hresult();
    }
    if (SUCCEEDED(apartment)) RoUninitialize();
    return result;
}
