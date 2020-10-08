package com.koupper.octopus

interface DeploymentManager {
    fun toDeployableProjectNamed(name: String): DeploymentManager
}