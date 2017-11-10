# -*- coding: utf-8

# Copyright 2017-present Open Networking Foundation
#
# Licensed under the Apache License, Version 2.0 (the "License"). You
# may not use this file except in compliance with the License. A copy of
# the License is located at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# or in the "license" file accompanying this file. This file is
# distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF
# ANY KIND, either express or implied. See the License for the specific
# language governing permissions and limitations under the License.

from . import Command, Action, Resource, command
import requests


class LockResource(Resource):
    def _get_lock_names(self):
        response = requests.get(self.cli.path('/v1/primitives/locks'))
        if response.status_code == 200:
            return response.json()
        return []

    def suggest(self, prefix):
        locks = self._get_lock_names()
        for lock in locks:
            if lock.lower().startswith(prefix.lower()):
                return lock[len(prefix):]
        return None

    def complete(self, prefix):
        locks = self._get_lock_names()
        for lock in locks:
            if lock.lower().startswith(prefix.lower()):
                yield lock


class LockAction(Action):
    def execute(self, name):
        response = requests.post(self.cli.path('/v1/primitives/locks/{name}', name=name))
        if response.status_code == 200:
            print(response.json())
        else:
            print("Failed to acquire lock")


class UnlockAction(Action):
    def execute(self, name):
        response = requests.delete(self.cli.path('/v1/primitives/locks/{name}', name=name))
        if response.status_code == 200:
            print(response.json())
        else:
            print("Failed to release lock")


@command(
    'lock {id} lock',
    type=Command.Type.PRIMITIVE,
    id=LockResource,
    lock=LockAction
)
@command(
    'lock {id} unlock',
    type=Command.Type.PRIMITIVE,
    id=LockResource,
    unlock=UnlockAction
)
class LockCommand(Command):
    """Lock command"""
