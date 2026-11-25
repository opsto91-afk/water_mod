#include "TouchRouter.hpp"

#include <algorithm>
#include <cfloat>

namespace cm {

namespace {
bool isDown(int a) { return a == 0 || a == 5; }
bool isUp(int a) { return a == 1 || a == 6; }
bool isCancel(int a) { return a == 3; }
}  // namespace

bool TouchRouter::hitUi(ImVec2 p) const {
  for (const Rect& z : pubZones_)
    if (inside(z, p)) return true;
  for (const Rect& b : pubBlocks_)
    if (inside(b, p)) return true;
  return false;
}

bool TouchRouter::offer(const TouchEv& e) {
  const int a = e.action & 0xFF;
  std::lock_guard<std::mutex> l(mu_);
  auto it = std::find(ownedIds_.begin(), ownedIds_.end(), e.id);
  const bool owned = it != ownedIds_.end();

  if (isDown(a)) {
    if (owned) ownedIds_.erase(it);  // stale entry (lost UP): start fresh
    if (!hitUi(ImVec2(e.x, e.y))) return false;  // game keeps it
    ownedIds_.push_back(e.id);
    if (queue_.size() < 1024) queue_.push_back(e);
    return true;
  }
  if (!owned) return false;  // a game finger: never touch it
  if (queue_.size() < 1024) queue_.push_back(e);
  if (isUp(a) || isCancel(a)) ownedIds_.erase(it);
  return true;
}

void TouchRouter::beginZones() {
  next_.fill(Rect{});
  nextBlocks_.clear();
}

void TouchRouter::setZone(ZoneId z, ImVec2 mn, ImVec2 mx) {
  next_[z].mn = mn;
  next_[z].mx = mx;
  next_[z].active = true;
}

void TouchRouter::setBlock(ImVec2 mn, ImVec2 mx) {
  Rect r;
  r.mn = mn;
  r.mx = mx;
  r.active = true;
  nextBlocks_.push_back(r);
}

bool TouchRouter::takeClick(ZoneId z) {
  const bool c = click_[z];
  click_[z] = false;
  return c;
}

bool TouchRouter::zoneRect(ZoneId z, ImVec2& mn, ImVec2& mx) const {
  mn = cur_[z].mn;
  mx = cur_[z].mx;
  return cur_[z].active;
}

int TouchRouter::hitZone(ImVec2 p) const {
  for (int z = 0; z < ZCount; ++z)
    if (inside(cur_[z], p)) return z;
  return -1;
}

void TouchRouter::beginFrame(ImGuiIO& io) {
  std::vector<TouchEv> events;
  {
    std::lock_guard<std::mutex> l(mu_);
    cur_ = next_;               // areas registered during the previous frame ...
    pubZones_ = next_;          // ... are also what the input thread decides with
    pubBlocks_ = nextBlocks_;
    events.swap(queue_);
  }

  for (const TouchEv& e : events) {
    const int a = e.action & 0xFF;
    const ImVec2 pos(e.x, e.y);

    if (isDown(a)) {
      Ptr* p = nullptr;
      for (Ptr& q : ptrs_)
        if (q.id == e.id) p = &q;
      if (!p) {
        ptrs_.push_back(Ptr{});
        p = &ptrs_.back();
        p->id = e.id;
      }
      p->pos = pos;
      p->owner = -1;
      const int z = hitZone(pos);
      if (z >= 0) {
        if (!owned_[z]) {
          p->owner = z;
          owned_[z] = true;
          held_[z] = true;
        }
      } else if (uiPtr_ < 0) {
        p->owner = kOwnUI;
        uiPtr_ = e.id;
        io.AddMousePosEvent(pos.x, pos.y);
        io.AddMouseButtonEvent(0, true);
      }
    } else if (a == 2) {
      for (Ptr& q : ptrs_)
        if (q.id == e.id) {
          q.pos = pos;
          if (q.owner == kOwnUI) io.AddMousePosEvent(pos.x, pos.y);
        }
    } else if (isUp(a) || isCancel(a)) {
      for (size_t i = 0; i < ptrs_.size(); ++i) {
        if (ptrs_[i].id != e.id) continue;
        const int owner = ptrs_[i].owner;
        if (owner == kOwnUI) {
          io.AddMousePosEvent(pos.x, pos.y);
          io.AddMouseButtonEvent(0, false);
          io.AddMousePosEvent(-FLT_MAX, -FLT_MAX);
          uiPtr_ = -1;
        } else if (owner >= 0 && owner < ZCount) {
          if (isUp(a) && inside(cur_[owner], pos)) click_[owner] = true;
          held_[owner] = false;
          owned_[owner] = false;
        }
        ptrs_.erase(ptrs_.begin() + i);
        break;
      }
    }
  }
}

void TouchRouter::releaseAll(ImGuiIO& io) {
  {
    std::lock_guard<std::mutex> l(mu_);
    queue_.clear();
    ownedIds_.clear();
    pubZones_.fill(Rect{});
    pubBlocks_.clear();
  }
  if (uiPtr_ >= 0) {
    io.AddMouseButtonEvent(0, false);
    io.AddMousePosEvent(-FLT_MAX, -FLT_MAX);
  }
  ptrs_.clear();
  owned_.fill(false);
  held_.fill(false);
  click_.fill(false);
  uiPtr_ = -1;
}

}  // namespace cm
