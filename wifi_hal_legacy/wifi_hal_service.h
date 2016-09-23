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
#include <android-base/macros.h>
#include <hardware_legacy/wifi_hal.h>
#include <utils/Looper.h>

#include "wifi_hal_state.h"

namespace android {
namespace hardware {
namespace wifi {

class WifiChipService;

class WifiHalService : public V1_0::IWifi {
 public:
  WifiHalService(sp<Looper>& looper);

  Return<void> registerEventCallback(
      const sp<V1_0::IWifiEventCallback>& callback) override;

  Return<bool> isStarted() override;
  Return<void> start() override;
  Return<void> stop() override;

  Return<void> getChip(getChip_cb cb) override;

 private:
  const wifi_interface_handle kInterfaceNotFoundHandle = nullptr;
  /** Get a HAL interface handle by name */
  wifi_interface_handle FindInterfaceHandle(const std::string& ifname);

  /**
   * Called to indicate that the HAL implementation cleanup may be complete and
   * the rest of HAL cleanup should be performed.
   */
  void FinishHalCleanup();

  /**
   * Entry point for HAL event loop thread. Handles cleanup when terminating.
   */
  void DoHalEventLoop();

  std::set<sp<V1_0::IWifiEventCallback>> callbacks_;
  sp<WifiChipService> chip_;

  WifiHalState state_;
  std::thread event_loop_thread_;

  // Variables to hold state while stopping the HAL
  bool awaiting_hal_cleanup_command_;
  bool awaiting_hal_event_loop_termination_;

  DISALLOW_COPY_AND_ASSIGN(WifiHalService);
};

}  // namespace wifi
}  // namespace hardware
}  // namespace android

#endif  // WIFI_HAL_LEGACY_WIFI_HAL_SERVICE_H_
