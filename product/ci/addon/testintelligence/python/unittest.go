// Copyright 2021 Harness Inc. All rights reserved.
// Use of this source code is governed by the PolyForm Free Trial 1.0.0 license
// that can be found in the licenses directory at the root of this repository, also available at
// https://polyformproject.org/wp-content/uploads/2020/05/PolyForm-Free-Trial-1.0.0.txt.

/*
Package python
Any Python application that can run through the unittest CLI
should be able to use this to perform test intelligence.

Test filtering:
unittest test
*/
package python

import (
	"context"
	"errors"
	"github.com/harness/harness-core/commons/go/lib/exec"
	"github.com/harness/harness-core/commons/go/lib/filesystem"
	"github.com/harness/harness-core/product/ci/ti-service/types"
	"go.uber.org/zap"
)

var (
	unittestCmd = "unittest"
)

type unittestRunner struct {
	fs                filesystem.FileSystem
	log               *zap.SugaredLogger
	cmdContextFactory exec.CmdContextFactory
	agentPath         string
}

func NewUnittestRunner(log *zap.SugaredLogger, fs filesystem.FileSystem, factory exec.CmdContextFactory, agentPath string) *unittestRunner {
	return &unittestRunner{
		fs:                fs,
		log:               log,
		cmdContextFactory: factory,
		agentPath:         agentPath,
	}
}

func (b *unittestRunner) AutoDetectPackages() ([]string, error) {
	return []string{}, errors.New("not implemented")
}

func (b *unittestRunner) AutoDetectTests(ctx context.Context, testGlobs []string) ([]types.RunnableTest, error) {
	return GetPythonTests(testGlobs)
}

func (b *unittestRunner) GetCmd(ctx context.Context, tests []types.RunnableTest, userArgs, agentConfigPath string, ignoreInstr, runAll bool) (string, error) {
	return "", nil
}
