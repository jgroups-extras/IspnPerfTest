Ansible Provisioning
======================

Provision the resources to run IspnPerf in a cloud environment.

Requirements
------------

The controller node requires Ansible and `rsync` to upload files remotely.
The nodes provisioned by Ansible require sshd and the correct keys uploaded.

Playbooks
---------

There are multiple playbooks to help manage dependencies, files, start and stop the test.

### perf.yml

This playbook allows to interact with the provisioned nodes to synchronize and install dependencies.
This should be run before trying to run any test.
This is defined by which kind of operation to perform:

* `-e operation=upload`: Will synchronize the files from the parent folder in all nodes.
This should be executed after changes in the configuration files or the Java source.
You need to recompile the project and synchronize. This step uploads the scripts in `bin/`, the compiled source (`target/`) and the environment file `env`.
* `-e operation=init`: Install the dependencies to run the tests.

To run this playbook the inventory file is required. You can run as:

```bash
$ ansible-playbook -i inventory.yaml perf.yml -e operation=[init|upload]
```

> [!NOTE]
> Running without the `-e operation` argument runs the `init` and `upload` steps.

### start.yml

This playbook will start IspnPerf in `-nohup` mode with the provided arguments:

* `-e script_file`: To provide which script in the `bin/` folder to execute. Defaults to `perf-test.sh`.
* `-e control_config`: Which configuration to utilize for control nodes. Defaults to `control-tcp.sh`.
* `-e cache_config`: Which configuration to utilize for the cache. Defaults to `dist-sync.xml`.
* `-e java_opts`: Provide custom arguments to the JVM, for example, changing garbage collection. Defaults to empty string.

```bash
$ ansible-playbook -i inventory.yaml start.yml
```

> [!HINT]
> The controller node will not start the test. It should be started manually through SSH.

### stop.yml

This playbook will stop the Java process running on all nodes.

```bash
$ ansible-playbook -i inventory.yaml stop.yml
```

EC2 Provision
-------------

It is also now possible to run this benchmark on AWS EC2 machines automatically.

To do this you must install the required AWS EC2 roles
- Run command `ansible-galaxy collection install -r roles/ec2-requirements.yml`

Now all that is needed is to set your EC2 Secret Key environment variables
- `export AWS_ACCESS_KEY_ID=<Key Id>`
- `export AWS_SECRET_ACCESS_KEY=<Key>`

### aws-ec2.yml

This will provision and manage instances.
The required arguments are the region (e.g `-e region=us-east-2`) and operation (e.g `-e operation=create`).
* The operations supported are `create` and `delete`.
    * `create` - required to be ran first. This creates the configured instances, a local ssh file, and a local inventory file to interact with them.
    * `delete` - deletes all the instances, security group, ssh key (remote and local) and the local inventory

Note after doing `create` the working directory will contain two new files:
* `benchmark_${user}_${region}.pem` file is the private ssh to key to connect to the EC2 machines
* `benchmark_${user}_${region}_inventory.yaml`

All the instances defaults are found at [main.yaml](roles/aws_ec2/defaults/main.yml).
You can override these values via normal ansible means https://docs.ansible.com/ansible/latest/playbook_guide/playbooks_variables.html#variable-precedence-where-should-i-put-a-variable.

You can run this playbook with:

```bash
$ ansible-playbook -i , aws-ec2.yml -e operation=create -e region=sa-east-1 
```

> [!IMPORTANT]
> The empty inventory argument **must** be provided. 


#### SSH

The AWS instances are open for SSH from the Ansible control node only.
You can SSH by utilizing the generated key and utilizing the Ansible user name.
The user name is available on the inventory file.

In the uploaded files, there is an `env` file, which defines environment variables to help utilize the discovery protocols.
Before running the bash script to initialize the perf test, execute the following to populate the variables:

```bash
$ . ./env [optional-java-arguments]
```

This will create the variables `INITIAL_HOSTS_CONTROL` for the control nodes to discover each other, `INITIAL_HOSTS` for
the cache nodes to discover each other, and `JAVA_OPTS` populated with arguments in `[optional-java-arguments]`.
For example:

```bash
$ . ./env "-XX:+UseZGC -XX:+ZGenerational"
```


License
-------

Apache License, Version 2.0
