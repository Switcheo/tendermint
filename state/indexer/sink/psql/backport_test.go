package psql

import (
	"github.com/switcheo/tendermint/state/indexer"
	"github.com/switcheo/tendermint/state/txindex"
)

var (
	_ indexer.BlockIndexer = BackportBlockIndexer{}
	_ txindex.TxIndexer    = BackportTxIndexer{}
)
