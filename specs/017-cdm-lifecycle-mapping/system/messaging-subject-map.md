# Messaging Subject Map (State 017)

## Subject Families

- `/trades`
  - producer: `trade-service`
  - consumer: `trade-processor`
  - delivery: `point-to-point`
  - wildcard: `no`
  - scope: `global`
  - payload: validated trade order with stamped execution price

- `/accounts/<accountId>/trades`
  - producer: `trade-processor`
  - consumer: frontend trade blotter stream
  - delivery: `broadcast`
  - wildcard: `no`
  - scope: `per-account`
  - payload: processed trade (includes `price`)

- `/accounts/<accountId>/positions`
  - producer: `trade-processor`
  - consumer: frontend position blotter stream
  - delivery: `broadcast`
  - wildcard: `no`
  - scope: `per-account`
  - payload: position snapshot (includes `averageCostBasis`)

- `pricing.<TICKER>`
  - producer: `price-publisher`
  - consumer: frontend valuation streams
  - delivery: `broadcast`
  - wildcard: `yes` (`pricing.*`)
  - scope: `per-ticker`
  - payload: market tick (`price`, `openPrice`, `closePrice`, `asOf`, `source`)

- `/accounts/<accountId>/orders`
  - producer: `order-matcher`
  - consumer: frontend account order blotter stream
  - delivery: `broadcast`
  - wildcard: `no`
  - scope: `per-account`
  - payload: order lifecycle event (`orderId`, `status`, `remainingQuantity`, `limitPrice`, `lastExecutionPrice`)

- `/orders`
  - producer: `order-matcher`
  - consumer: frontend admin order blotter stream
  - delivery: `broadcast`
  - wildcard: `no`
  - scope: `global`
  - payload: order lifecycle event (`orderId`, `accountId`, `status`, `remainingQuantity`, `limitPrice`)

## State 017 additions

All subjects above keep their 009 payloads. New order/trade fields (`orderId`, `filledQuantity`,
`canceledQuantity`, `cdmRef`) are optional.

- `/trades/booked`
  - producer: `trade-processor`
  - consumer: `cdm-service` (lifecycle adapter)
  - delivery: `broadcast`
  - wildcard: `no`
  - scope: `global`
  - payload: committed (booked) trade, published after the trade transaction commits (includes `orderId` for order fills)

- `/orders/rejected`
  - producer: `order-matcher`
  - consumer: `cdm-service` (lifecycle adapter)
  - delivery: `broadcast`
  - wildcard: `no`
  - scope: `global`
  - payload: rejected order request (rejection id, reason, submitted fields)

- `cdm.workflow`
  - producer: `cdm-service`
  - consumer: CDM views / external CDM consumers
  - delivery: `broadcast`
  - wildcard: `no`
  - scope: `global`
  - payload: stored CDM `WorkflowStep` (`cdmRef`, `globalKey`, `cdmType`, `validation`, `object`)

- `cdm.events`
  - producer: `cdm-service`
  - consumer: CDM views / external CDM consumers
  - delivery: `broadcast`
  - wildcard: `no`
  - scope: `global`
  - payload: stored CDM `BusinessEvent` (`cdmRef`, `globalKey`, `cdmType`, `validation`, `object`)

- `cdm.tradestate`
  - producer: `cdm-service`
  - consumer: CDM views / external CDM consumers
  - delivery: `broadcast`
  - wildcard: `no`
  - scope: `global`
  - payload: stored CDM `TradeState` (`cdmRef`, `globalKey`, `cdmType`, `validation`, `object`)
