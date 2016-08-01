/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef WIFI_HAL_LEGACY_WIFI_HAL_SERVICE_H_
#define WIFI_HAL_LEGACY_WIFI_HAL_SERVICE_H_

#include <functional>
#include <set>
#include <thread>

#include <android/hardware/wifi/1.0/IWifi.h>
#include <hardware_legacy/wifi_hal.h>
#include <utils/Looper.h>

namespace android {
namespace hardware {
namespace wifi {

using android::hardware::Status;
using android::hardware::wifi::V1_0::IWifi;
using android::hardware::wifi::V1_0::IWifiChip;
using android::hardware::wifi::V1_0::IWifiEventCallback;

class WifiHalService : public IWifi {
 public:
  WifiHalService(android::sp<android::Looper>& looper);

  Status registerEventCallback(const sp<IWifiEventCallback>& callback) override;

  Status start(uint32_t cmd_id) override;
  Status stop(uint32_t cmd_id) override;

  Status getChip(std::function<void(const sp<IWifiChip>& chip)> cb) override;

 private:
  /**
   * Called to indicate that the HAL implementation cleanup may be complete and
   * the rest of HAL cleanup should be performed.
   */
  void FinishHalCleanup();

  /**
   * Entry point for HAL event loop thread. Handles cleanup when terminating.
   */
  void DoHalEventLoop();

  /** Post a task to be executed on the main thread */
  void PostTask(const std::function<void()>& callback);

  android::sp<Looper> looper_;
  std::set<sp<IWifiEventCallback>> callbacks_;

  enum class State {
    STOPPED,
    STARTED,
    STOPPING
  };

  State state_;
  wifi_hal_fn hal_func_table_;
  /** opaque handle from vendor for use while HAL is running */
  wifi_handle hal_handle_;
  /**
   * This thread is created when the HAL is started and runs the HAL event loop
   * (implemented in the vendor implementation). It's use is vendor specific,
   * but it can be used to dispatch async callbacks back to the HAL user. In
   * order to provide a simple threading model these calls will generally be
   * proxied back to the main thread, where the actual handling will occur. The
   * thread terminates when the HAL is cleaned up.
   */
  std::thread event_loop_thread_;

  // Variables to hold state while stopping the HAL
  uint32_t pending_stop_cmd_id_;
  bool awaiting_hal_cleanup_command_;
  bool awaiting_hal_event_loop_termination_;
};

}  // namespace wifi
}  // namespace hardware
}  // namespace android

#endif  // WIFI_HAL_LEGACY_WIFI_HAL_SERVICE_H_
