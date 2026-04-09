# Booking Payment Phase 1 Foundation Blueprint

## Confirmed Flow Scope

### Labour booking
- User can book a specific labour directly.
- A directly-booked labour is visible only when:
  - labour is online
  - labour is not already booked
  - user location falls within labour service radius
- Direct request expires if not accepted within `30` seconds.
- User can broadcast a labour request globally using filters such as price range.
- Broadcast request goes only to labour who match:
  - price range
  - online status
  - not booked
  - user location inside service radius
- First labour who accepts wins the booking.
- All other pending labour requests for the same broadcast must be closed immediately.
- After labour accepts:
  - user is notified
  - user is asked to complete payment
- After payment success:
  - labour receives user contact details
  - user receives labour contact details
- Labour must reach within the configured arrival timeline.
- User can cancel after the arrival timeline is exceeded.
- Repeated no-shows in a month can suspend labour temporarily.
- Start work requires start OTP from user.
- Completion requires completion OTP from user.
- Mutual cancel after arrival/start discussion requires cancel OTP.
- User-side cancellation after work start is penalized on the next booking/order.
- In user-side post-start cancellation:
  - booking amount is not refunded to user
  - half amount goes to labour

### Service booking
- Search and request flow mirrors labour.
- Difference:
  - service provider may have multiple available servicemen
  - booking is allowed until available servicemen reaches `0`
  - capacity decreases on accept and increases on completion/cancel release
- Service provider becomes non-searchable when all servicemen are occupied.
- Arrival timeline is category-based.
  - automobile example: `45` minutes
  - other categories: same-day
  - final values will come from admin settings

### Shop order
- User can add items from only one shop in cart at a time.
- Adding items from another shop requires clearing old cart after confirmation.
- User pays before order placement is finalized.
- Delivery is handled by the shop itself.
- Shop delivery fee can depend on shop rules, for example minimum order threshold.
- Final payable includes:
  - item total
  - taxes
  - delivery fee if applicable
  - platform fee
- Order cannot be cancelled once it is out for delivery.

### Common rules
- Notify on:
  - payment success
  - payment failure
  - booking accepted
  - booking rejected
  - order/booking lifecycle changes
- Platform fee applies to labour, service, and shop flows.
- Platform fee values come from admin settings.

## Existing DDL Coverage

Current DDL already defines the main tables for:
- `bookings`
- `booking_line_items`
- `booking_status_history`
- `orders`
- `order_items`
- `order_status_history`
- `payments`
- `payment_attempts`
- `payment_transactions`
- `refunds`
- `penalties`
- `suspensions`

## Important Gaps To Handle In Module Design

The current DDL is a strong base, but Phase 2+ implementation must account for these flow requirements that are not fully represented yet:

1. Direct vs broadcast request mode
- We need explicit request mode and request fan-out tracking.
- A single booking record is not enough to model multiple pending provider candidates.

2. Accept-within-30-seconds logic
- We need request expiry tracking and a scheduler/timeout processor.

3. First-accept-wins broadcast flow
- We need candidate request rows that can be atomically closed for all losers.

4. Reach timeline and no-show tracking
- We need arrival SLA fields and monthly no-show counters or queryable history.

5. Start/completion/mutual-cancel OTP flow
- We need booking work-session OTP records or a generic booking action OTP table.

6. User-side cancellation penalty carry-forward
- We need a penalty ledger that can be consumed on the next booking/order payment.

7. Service capacity reservation
- We need service-capacity locking/release logic tied to accept/cancel/complete transitions.

8. Category-based arrival policy
- We need rule resolution from admin settings and/or category mappings.

9. Shop single-cart rule
- This is primarily user-app/cart logic, but booking-payment must still validate shop consistency at checkout time.

## Phase 1 Deliverables

Phase 1 foundation for this module means:
- finalize domain vocabulary and status enums
- create settings key constants for admin-config-driven rules
- keep Firebase and Razorpay bootstrapping ready
- document DDL gaps before API implementation starts

## Next Build Order

1. Create persistence entities/repositories for bookings, orders, payments, penalties, and suspensions.
2. Add provider candidate request model for direct and broadcast matching.
3. Build booking create APIs.
4. Build Razorpay payment initiation and verification.
5. Add accept/reject and timeout processors.
6. Add notification dispatch through Firebase.
