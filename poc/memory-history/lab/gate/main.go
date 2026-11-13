// Command gate is the decision service of the lab (Contract L2).
//
// It answers the external routing call of Trino Gateway (POST /gateway/route) and the
// ext_authz HTTP call of Envoy (/envoy/check/<group>/...). For a query that the predictor
// knows, it adds the session properties required_workers_count and
// required_workers_max_wait_time. In every other case, and on every error, it adds nothing.
//
// Wait modes (Contract L6): `wait` adds the properties, `signal` only makes the reservation, and
// `bounded:<seconds>` makes the reservation and holds the answer until the coordinator of the group has
// the required active workers (GATE_NODES_<GROUP>) or the seconds passed.
package main

import (
	"log"
	"net/http"
	"os"
	"strconv"
	"time"
)

func main() {
	cfg, err := configFromEnv(os.Getenv)
	if err != nil {
		log.Fatalf("invalid configuration: %v", err)
	}
	g := newGate(cfg)
	addr := ":" + strconv.Itoa(cfg.port)
	log.Printf("gate listening on %s, groups=%v default=%s predictor-timeout=%s delay=%s",
		addr, cfg.groupNames(), cfg.defaultGroup, cfg.predictorTimeout, cfg.delay)
	log.Printf("wait modes: default=%s group-modes=%v max-bounded-seconds=%d nodes=%v",
		cfg.defaultMode, cfg.groupModes, cfg.maxBoundedSeconds, cfg.nodes)
	server := &http.Server{
		Addr:              addr,
		Handler:           g.handler(),
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       120 * time.Second,
	}
	log.Fatal(server.ListenAndServe())
}
